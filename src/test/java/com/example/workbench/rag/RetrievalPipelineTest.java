package com.example.workbench.rag;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 检索管线的行为锁定：查询构建五步与 RRF 融合顺序。
 *
 * <p>这些断言的价值在于「拆分前后必须一致」——合并多个查询、保留原问题、按 RRF 排序后
 * 再用稳定键兜底，任何一处口径变了都会让召回集合变，进而影响 MRR。</p>
 */
class RetrievalPipelineTest {

    private final RetrievalPipeline pipeline = new RetrievalPipeline(
            new FixedVectorStore(), null, null, false, 60, 3, false, false, 4, false, "distance");

    @Test
    @DisplayName("未开启改写与多查询时，检索查询就是原问题的领域扩展")
    void keepsOriginalQuestionWhenSwitchesOff() {
        List<String> queries = pipeline.queries("什么是 RAG", new RagChatOptions(false, false));

        assertThat(queries).containsExactly("什么是 RAG");
    }

    @Test
    @DisplayName("领域词扩展按主题追加同义检索词")
    void expandsDomainTerms() {
        assertThat(RetrievalPipeline.expandQuery("Embedding 是什么")).contains("向量表示 语义表示");
        assertThat(RetrievalPipeline.expandQuery("Chunk 怎么切")).contains("文档片段");
        assertThat(RetrievalPipeline.expandQuery("普通问题")).isEqualTo("普通问题");
    }

    @Test
    @DisplayName("模型输出的编号与引号在生成查询时被清洗")
    void cleansGeneratedQuery() {
        assertThat(RetrievalPipeline.cleanQuery("  1. RAG 核心流程  ")).isEqualTo("RAG 核心流程");
        assertThat(RetrievalPipeline.cleanQuery("\"向量检索\"")).isEqualTo("向量检索");
        assertThat(RetrievalPipeline.cleanQuery(null)).isEmpty();
    }

    @Test
    @DisplayName("独立问题只接受 RELATED: 前缀，其余一律视为不可用")
    void parsesStandaloneQuestion() {
        assertThat(RetrievalPipeline.parseStandaloneQuestion("RELATED: 它的核心流程是什么", "它呢"))
                .isEqualTo("它的核心流程是什么");
        assertThat(RetrievalPipeline.parseStandaloneQuestion("INDEPENDENT", "它呢")).isNull();
        assertThat(RetrievalPipeline.parseStandaloneQuestion("RELATED: 它呢", "它呢")).isNull();
        assertThat(RetrievalPipeline.parseStandaloneQuestion(null, "它呢")).isNull();
    }

    @Test
    @DisplayName("单通道召回按 RRF 名次排序，并记录通道与名次轨迹")
    void fusesDenseRanking() {
        RetrievalPipeline.Outcome outcome = pipeline.retrieve(
                List.of("RAG"), "", null, source -> true);

        assertThat(outcome.sources()).extracting(SourceDocument::id).containsExactly("a", "b");
        CandidateTrace first = outcome.traces().get(RetrievalRanker.stableSourceKey(outcome.sources().get(0)));
        assertThat(first.channel()).isEqualTo("DENSE");
        assertThat(first.denseRank()).isEqualTo(1);
        assertThat(first.fusionScore()).isEqualTo(1.0 / 61);
    }

    @Test
    @DisplayName("可见性过滤在融合前生效：被拒绝的候选不进入结果也不留轨迹")
    void filtersInvisibleCandidates() {
        RetrievalPipeline.Outcome outcome = pipeline.retrieve(
                List.of("RAG"), "", null, source -> !"a".equals(source.id()));

        assertThat(outcome.sources()).extracting(SourceDocument::id).containsExactly("b");
        assertThat(outcome.traces()).hasSize(1);
    }

    /** 固定返回两条候选的向量库桩，避免测试依赖真实向量检索。 */
    private static final class FixedVectorStore implements VectorStore {
        private final List<SourceDocument> all = new ArrayList<>();

        private FixedVectorStore() {
            all.add(doc("a", "第一段", 0.1));
            all.add(doc("b", "第二段", 0.2));
        }

        @Override
        public void clear() {
            all.clear();
        }

        @Override
        public void deleteByIds(List<String> ids) {
            all.removeIf(document -> ids.contains(document.id()));
        }

        @Override
        public void addAll(List<SourceDocument> documents) {
            all.addAll(documents);
        }

        @Override
        public void replaceAll(List<SourceDocument> documents) {
            all.clear();
            all.addAll(documents);
        }

        @Override
        public List<SourceDocument> similaritySearch(String query, int topK) {
            return all.stream().limit(topK).toList();
        }

        private static SourceDocument doc(String id, String content, double score) {
            return new SourceDocument(id, content, id, "src-" + id, "path-" + id, 0,
                    "doc-" + id, id + ".md", "hash-" + id, score, id, 0, 0, content.length(), "text-paragraph");
        }
    }
}
