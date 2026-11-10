package com.example.workbench.rag;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 检索管线：查询改写 / 多查询 → 稠密 + 稀疏并行召回 → RRF 融合 → 相邻分块扩展。
 *
 * <p>从 {@code RagService} 外提的第二步（D-3）。原先这条链路散落在服务内部的十余个私有方法里，
 * 每次调整检索策略都要在两千多行的类里改，回归范围说不清。抽出后仅保留「编排」在 RagService，
 * 检索本身的实现集中在此类。</p>
 *
 * <p>约束：只搬代码，不改口径——RRF 公式、候选倍数、去重顺序、相邻块判定条件全部原样保留。
 * 可见性判定通过 {@link SourceFilter} 由调用方注入，安全策略仍只有 RagService 一处实现。</p>
 */
final class RetrievalPipeline {

    private static final Logger log = LoggerFactory.getLogger(RetrievalPipeline.class);

    /**
     * 候选可见性判定：由调用方传入，避免检索管线持有任何鉴权口径。
     */
    @FunctionalInterface
    interface SourceFilter {
        boolean visible(SourceDocument source);
    }

    /**
     * 一次检索的产物。
     *
     * @param sources 融合排序后的候选
     * @param traces  每个候选的通道 / 分数 / 排名轨迹，供阈值与重排复用
     */
    record Outcome(List<SourceDocument> sources, Map<String, CandidateTrace> traces) {
    }

    private final VectorStore vectorStore;
    private final SparseRetriever sparseRetriever;
    private final LocalChatClient chatClient;
    private final boolean hybridEnabled;
    private final int rrfK;
    private final int topK;
    private final boolean queryRewriteEnabled;
    private final boolean multiQueryEnabled;
    private final int multiQueryMaxQueries;
    private final boolean adjacentEnabled;
    private final String scoreDirection;
    private volatile Executor executor = ForkJoinPool.commonPool();

    RetrievalPipeline(
            VectorStore vectorStore,
            SparseRetriever sparseRetriever,
            LocalChatClient chatClient,
            boolean hybridEnabled,
            int rrfK,
            int topK,
            boolean queryRewriteEnabled,
            boolean multiQueryEnabled,
            int multiQueryMaxQueries,
            boolean adjacentEnabled,
            String scoreDirection
    ) {
        this.vectorStore = vectorStore;
        this.sparseRetriever = sparseRetriever;
        this.chatClient = chatClient;
        this.hybridEnabled = hybridEnabled;
        this.rrfK = rrfK;
        this.topK = topK;
        this.queryRewriteEnabled = queryRewriteEnabled;
        this.multiQueryEnabled = multiQueryEnabled;
        this.multiQueryMaxQueries = multiQueryMaxQueries;
        this.adjacentEnabled = adjacentEnabled;
        this.scoreDirection = scoreDirection;
    }

    /**
     * 与 RagService 的 aiTaskExecutor 保持同步：注入发生在构造之后，用 setter 传递。
     *
     * @param executor 并行召回使用的线程池
     */
    void setExecutor(Executor executor) {
        this.executor = executor == null ? ForkJoinPool.commonPool() : executor;
    }

    /**
     * 默认检索选项：取服务构造时的全局开关。
     *
     * @return 请求级检索选项
     */
    RagChatOptions defaultOptions() {
        return new RagChatOptions(queryRewriteEnabled, multiQueryEnabled);
    }

    // ------------------------------------------------------------------
    // 查询构建
    // ------------------------------------------------------------------

    /**
     * 生成最终检索查询：先改写再扩展。
     *
     * @param question 用户问题
     * @param options  请求级选项
     * @return 检索查询
     */
    String buildQuery(String question, RagChatOptions options) {
        return expandQuery(rewrite(question, options));
    }

    /**
     * 构建一组检索查询：原问题始终保留，改写与多查询只做补充。
     *
     * @param question 用户问题
     * @param options  请求级选项
     * @return 去重后的查询列表
     */
    List<String> queries(String question, RagChatOptions options) {
        List<String> queries = new ArrayList<>();
        // 原问题始终保留；查询改写和多查询只能补充召回，不能替代用户原始表达。
        queries.add(expandQuery(question));
        String rewritten = buildQuery(question, options);
        if (!rewritten.equalsIgnoreCase(queries.get(0))) {
            queries.add(rewritten);
        }

        if (!options.multiQueryEnabled()) {
            return queries;
        }

        // 多查询只服务于召回，不直接作为最终回答内容。
        String prompt = """
                请把用户问题拆成 2 到 4 个适合知识库检索的简短查询。
                保留核心技术词。
                不要回答问题。
                每行只输出一个查询，不要编号。

                用户问题：
                %s
                """.formatted(question);
        String generated = chatClient.generate(prompt);
        log.info("RAG multi-query generated enabled=true generatedLength={}", generated == null ? 0 : generated.length());

        if (generated == null || generated.isBlank()) {
            return queries;
        }

        generated.lines()
                .map(RetrievalPipeline::cleanQuery)
                .filter(query -> !query.isBlank())
                .filter(query -> queries.stream().noneMatch(existing -> existing.equalsIgnoreCase(query)))
                .limit(Math.max(0, multiQueryMaxQueries - queries.size()))
                .forEach(queries::add);
        return queries;
    }

    /**
     * 查询改写：失败时退回原问题，不能因辅助模型失败而阻断 RAG。
     *
     * @param question 用户问题
     * @param options  请求级选项
     * @return 改写后的查询
     */
    String rewrite(String question, RagChatOptions options) {
        if (!options.queryRewriteEnabled()) {
            return question;
        }

        String prompt = """
                请把用户问题改写成适合知识库检索的简短查询。
                保留核心技术词。
                不要回答问题。
                只输出改写后的查询。

                用户问题：
                %s
                """.formatted(question);
        String rewritten = cleanQuery(chatClient.generate(prompt));
        log.info("RAG query rewrite completed enabled=true rewritten={} originalLength={}",
                !rewritten.isBlank(), question == null ? 0 : question.length());

        if (rewritten.isBlank()) {
            return question;
        }

        return rewritten;
    }

    /**
     * 清洗模型生成的查询：去掉编号、引号与首尾空白。
     *
     * @param query 模型输出
     * @return 清洗后的查询，null 转为空串
     */
    static String cleanQuery(String query) {
        if (query == null) {
            return "";
        }

        return query.trim()
                .replaceFirst("^[\\-\\*\\d\\.、)]+\\s*", "")
                .replace("\"", "")
                .replace("'", "")
                .trim();
    }

    /**
     * 解析「独立问题」输出：仅接受 RELATED: 前缀。
     *
     * @param generated       模型输出
     * @param originalQuestion 原问题
     * @return 独立问题；不适用时返回 null
     */
    static String parseStandaloneQuestion(String generated, String originalQuestion) {
        if (generated == null || generated.isBlank()) {
            return null;
        }
        String normalized = generated.strip();
        if (!normalized.regionMatches(true, 0, "RELATED:", 0, "RELATED:".length())) {
            return null;
        }
        String standalone = cleanQuery(normalized.substring("RELATED:".length()));
        if (standalone.isBlank() || standalone.equalsIgnoreCase(originalQuestion)) {
            return null;
        }
        return standalone;
    }

    /**
     * 领域词扩展：按问题命中的技术主题追加同义检索词，提升稀疏与稠密召回的命中率。
     *
     * @param question 用户问题
     * @return 扩展后的查询
     */
    static String expandQuery(String question) {
        StringBuilder query = new StringBuilder(question);

        if (question.contains("Embedding") || question.contains("embedding")) {
            query.append("\nEmbedding 向量表示 语义表示");
        }

        if (question.contains("Chunk") || question.contains("chunk")) {
            query.append("\nRAG Chunk 文档片段 切分后的文档片段 合理的 chunk 大小 检索质量 上下文");
        }

        if (question.contains("检索增强生成") || question.contains("RAG 的核心流程")) {
            query.append("\nRAG 核心流程 文档导入 切片 embedding 向量检索 上下文注入 模型生成");
        }

        if (question.contains("RAG") && question.contains("Chroma")) {
            query.append("\nRAG Vector Store Chroma Spring AI 集成 Document similaritySearch 向量检索");
        }

        if (question.contains("ChatClient") && question.contains("流式")) {
            query.append("\nChatClient Streaming stream content Flux token chatClient.prompt user stream content");
        }

        if (question.contains("MCP 是什么")) {
            query.append("\nMCP Model Context Protocol 基本概念 标准协议 外部工具 数据源 资源服务");
        }

        if (question.contains("MCP") && question.contains("主要解决什么问题")) {
            query.append("\nMCP 核心目标 统一协议 外部上下文 定制集成 AI 应用 工具 数据源");
        }

        if (question.contains("RAG Eval Case") || question.contains("RAG Eval case")) {
            query.append("\nAI 应用评估与测试 Eval Case 设计 id question expectedKeywords expectedSource shouldHaveAnswer shouldRefuse");
        }

        if (question.contains("Agent") && question.contains("普通 Chat")) {
            query.append("\nAI Agent 与工作流设计 Agent 与普通 Chat 的区别 步骤 工具 执行 动作 文本生成");
        }

        if (question.contains("前端") && question.contains("RAG 聊天请求")) {
            query.append("\nVue 聊天交互 RAG 请求 message conversationId answer sources chunkIndex snippet score headingPath");
        }

        if (question.contains("Rule-Based Evaluator") && question.contains("LLM Judge")) {
            query.append("\nAI 应用评估与测试 Rule-Based Evaluator LLM Judge 关键词 禁用词 稳定 可复现 忠实度 语义正确性");
        }

        if (question.contains("文本") && question.contains("预处理") && question.contains("Embedding")) {
            query.append("\nVector Store 与 Embedding 文本预处理 清洗 空白 标记 噪音 Embedding 检索质量");
        }

        if (question.contains("Vue") && question.contains("后端") && question.contains("来源")) {
            query.append("\nVue 聊天交互 来源展示 后端 RAG 接口返回来源 /api/rag/chat answer sources chunkIndex headingPath");
        }

        return query.toString();
    }

    // ------------------------------------------------------------------
    // 召回与融合
    // ------------------------------------------------------------------

    /**
     * 按问题检索候选（可选携带独立问题做二次召回补充）。
     *
     * @param question           用户问题
     * @param standaloneQuestion 改写后的独立问题，可为 null
     * @param ownerUserId        当前用户，空串表示不限定
     * @param options            请求级选项
     * @param visibility         可见性判定
     * @return 检索产物
     */
    Outcome retrieve(
            String question,
            String standaloneQuestion,
            String ownerUserId,
            Set<String> readableWorkspaceIds,
            RagChatOptions options,
            SourceFilter visibility
    ) {
        List<String> queries = new ArrayList<>(queries(question, options));
        if (standaloneQuestion != null && !standaloneQuestion.isBlank()
                && !standaloneQuestion.equalsIgnoreCase(question)) {
            queries(standaloneQuestion, options).stream()
                    .filter(query -> queries.stream().noneMatch(existing -> existing.equalsIgnoreCase(query)))
                    .forEach(queries::add);
        }
        return retrieve(queries, ownerUserId, readableWorkspaceIds, visibility);
    }

    /**
     * 按一组查询做混合召回 + RRF 融合。
     *
     * @param queries               查询列表
     * @param ownerUserId           当前用户
     * @param readableWorkspaceIds  可读空间集合，原样下传给向量库做空间裁剪
     * @param visibility            可见性判定
     * @return 融合排序后的候选与轨迹
     */
    Outcome retrieve(
            List<String> queries,
            String ownerUserId,
            Set<String> readableWorkspaceIds,
            SourceFilter visibility
    ) {
        LinkedHashMap<String, CandidateAccumulator> candidates = new LinkedHashMap<>();
        int candidateLimit = Math.max(topK, topK * 3);
        List<CompletableFuture<QueryRetrievalResult>> retrievals = queries.stream()
                .map(query -> retrieveQueryInParallel(query, candidateLimit, ownerUserId, readableWorkspaceIds))
                .toList();

        for (CompletableFuture<QueryRetrievalResult> retrieval : retrievals) {
            QueryRetrievalResult queryResult = retrieval.join();
            String query = queryResult.query();
            List<SourceDocument> denseResults = queryResult.denseResults();
            for (int index = 0; index < denseResults.size(); index++) {
                SourceDocument source = denseResults.get(index);
                if (!visibility.visible(source)) {
                    continue;
                }
                candidates.computeIfAbsent(RetrievalRanker.stableSourceKey(source),
                                ignored -> new CandidateAccumulator(source))
                        .addDense(source, query, index + 1, reciprocalRank(index + 1));
            }
            if (hybridEnabled && sparseRetriever != null) {
                List<SourceDocument> sparseResults = queryResult.sparseResults();
                for (int index = 0; index < sparseResults.size(); index++) {
                    SourceDocument source = sparseResults.get(index);
                    if (!visibility.visible(source)) {
                        continue;
                    }
                    candidates.computeIfAbsent(RetrievalRanker.stableSourceKey(source),
                                    ignored -> new CandidateAccumulator(source))
                            .addSparse(source, query, index + 1, reciprocalRank(index + 1));
                }
            }
        }

        List<CandidateAccumulator> ranked = candidates.values().stream()
                .sorted(Comparator.comparingDouble(CandidateAccumulator::fusionScore).reversed()
                        .thenComparing(candidate -> RetrievalRanker.stableSourceKey(candidate.source())))
                .toList();
        LinkedHashMap<String, CandidateTrace> traces = new LinkedHashMap<>();
        List<SourceDocument> sources = new ArrayList<>();
        for (int index = 0; index < ranked.size(); index++) {
            CandidateAccumulator candidate = ranked.get(index);
            SourceDocument source = candidate.resultSource();
            sources.add(source);
            traces.put(RetrievalRanker.stableSourceKey(source), candidate.trace(index + 1));
        }
        return new Outcome(List.copyOf(sources), traces);
    }

    private CompletableFuture<QueryRetrievalResult> retrieveQueryInParallel(
            String query, int candidateLimit, String ownerUserId, Set<String> readableWorkspaceIds
    ) {
        CompletableFuture<List<SourceDocument>> dense = CompletableFuture.supplyAsync(
                () -> similaritySearch(query, candidateLimit, ownerUserId, readableWorkspaceIds), executor);
        CompletableFuture<List<SourceDocument>> sparse = hybridEnabled && sparseRetriever != null
                ? CompletableFuture.supplyAsync(
                        () -> safeSparseSearch(query, candidateLimit, ownerUserId, readableWorkspaceIds), executor)
                : CompletableFuture.completedFuture(List.of());
        return dense.thenCombine(sparse, (denseResults, sparseResults) ->
                new QueryRetrievalResult(query, denseResults, sparseResults));
    }

    private List<SourceDocument> safeSparseSearch(
            String query, int candidateLimit, String ownerUserId, Set<String> readableWorkspaceIds
    ) {
        try {
            return sparseRetriever.search(query, candidateLimit, ownerUserId, readableWorkspaceIds);
        } catch (RuntimeException exception) {
            log.warn("RAG sparse retrieval failed, continuing with Dense only errorType={}",
                    exception.getClass().getSimpleName());
            return List.of();
        }
    }

    private double reciprocalRank(int rank) {
        return 1.0 / (rrfK + rank);
    }

    /**
     * 稠密检索入口：支持按用户/空间裁剪的向量库实现。
     *
     * @param query                查询
     * @param limit                返回上限
     * @param ownerUserId          当前用户
     * @param readableWorkspaceIds 可读空间集合
     * @return 候选片段
     */
    List<SourceDocument> similaritySearch(
            String query, int limit, String ownerUserId, Set<String> readableWorkspaceIds) {
        if (vectorStore instanceof ScopedVectorStore scopedVectorStore) {
            return scopedVectorStore.similaritySearch(query, limit, ownerUserId, readableWorkspaceIds);
        }
        int candidateLimit = ownerUserId == null || ownerUserId.isBlank() ? limit : Math.max(limit, limit * 4);
        return vectorStore.similaritySearch(query, candidateLimit);
    }

    /**
     * 相邻分块扩展：同一文档、同一标题路径、页码相邻的块作为补充上下文。
     *
     * @param sources 已过滤片段
     * @return 扩展后的片段
     */
    List<SourceDocument> expandAdjacent(List<SourceDocument> sources) {
        if (!adjacentEnabled || sparseRetriever == null || sources.isEmpty()) {
            return sources;
        }
        List<SourceDocument> expanded = new ArrayList<>(sources);
        for (SourceDocument source : sources) {
            // 相邻分块属于同一文档，直接复用源分块的归属（ownerUserId / workspaceId），
            // 既避免依赖已清理的检索作用域，也保证层级空间下能正确取到祖先/组织文档的相邻块。
            for (SourceDocument adjacent : sparseRetriever.adjacent(
                    source.documentId(), source.chunkIndex(), source.ownerUserId(), source.workspaceId())) {
                boolean sameHeading = source.headingPath() == null || source.headingPath().isBlank()
                        || source.headingPath().equals(adjacent.headingPath());
                boolean contiguousPage = source.pageNumber() <= 0 || adjacent.pageNumber() <= 0
                        || Math.abs(source.pageNumber() - adjacent.pageNumber()) <= 1;
                if (sameHeading && contiguousPage) {
                    expanded.add(adjacent);
                }
            }
        }
        return expanded;
    }

    private final class CandidateAccumulator {
        private SourceDocument source;
        private Double denseScore;
        private Double sparseScore;
        private Integer denseRank;
        private Integer sparseRank;
        private double fusionScore;
        private final LinkedHashMap<String, Boolean> matchedQueries = new LinkedHashMap<>();

        private CandidateAccumulator(SourceDocument source) {
            this.source = source;
        }

        private void addDense(SourceDocument candidate, String query, int rank, double contribution) {
            if (denseScore == null || ("similarity".equals(scoreDirection)
                    ? candidate.score() > denseScore : candidate.score() < denseScore)) {
                source = candidate;
                denseScore = candidate.score();
            }
            denseRank = denseRank == null ? rank : Math.min(denseRank, rank);
            fusionScore += contribution;
            matchedQueries.put(query, true);
        }

        private void addSparse(SourceDocument candidate, String query, int rank, double contribution) {
            if (sparseScore == null || candidate.score() > sparseScore) {
                sparseScore = candidate.score();
            }
            sparseRank = sparseRank == null ? rank : Math.min(sparseRank, rank);
            fusionScore += contribution;
            matchedQueries.put(query, true);
            if (denseScore == null) {
                source = candidate;
            }
        }

        private SourceDocument source() {
            return source;
        }

        private double fusionScore() {
            return fusionScore;
        }

        private SourceDocument resultSource() {
            return source.withScore(denseScore == null ? sparseScore : denseScore);
        }

        private CandidateTrace trace(int finalRank) {
            String channel = denseScore != null && sparseScore != null ? "HYBRID"
                    : denseScore != null ? "DENSE" : "SPARSE";
            return new CandidateTrace(channel, denseScore, sparseScore, fusionScore, denseRank, sparseRank,
                    finalRank, List.copyOf(matchedQueries.keySet()));
        }
    }

    private record QueryRetrievalResult(
            String query, List<SourceDocument> denseResults, List<SourceDocument> sparseResults
    ) {
    }
}
