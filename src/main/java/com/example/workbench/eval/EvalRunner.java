package com.example.workbench.eval;

import com.example.workbench.auth.AppUser;
import com.example.workbench.rag.RagChatResponse;
import com.example.workbench.rag.RagChatRequest;
import com.example.workbench.rag.RagService;
import com.example.workbench.rag.RagChatOptions;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class EvalRunner {

    // 命令行评测同时输出文件报告，便于 CI 保存可追溯的运行产物。
    private static final Path RESULTS_PATH = Path.of("eval", "results", "latest.json");
    private static final Path REPORT_PATH = Path.of("eval", "reports", "latest.md");
    private static final DateTimeFormatter RUN_ID_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")
            .withZone(ZoneOffset.UTC);

    private final RagService ragService;
    private final RuleBasedEvaluator evaluator;
    private final LlmJudgeEvaluator llmJudgeEvaluator;
    private final ObjectMapper objectMapper;
    private final EvalRunStorage evalRunStorage;
    private final EvalQualityGate qualityGate;
    private final EvalDimensionSummarizer dimensionSummarizer;
    private final ContextRuleBasedEvaluator contextEvaluator;
    private final ParserRuleBasedEvaluator parserEvaluator;
    private final boolean judgeEnabled;
    private final Set<String> caseIds;
    private final int ragTopK;
    private final double similarityThreshold;
    private final String scoreDirection;
    private final boolean queryRewriteEnabled;
    private final boolean multiQueryEnabled;
    private final boolean retrievalDebugEnabled;

    public EvalRunner(
            RagService ragService,
            RuleBasedEvaluator evaluator,
            LlmJudgeEvaluator llmJudgeEvaluator,
            ObjectMapper objectMapper,
            EvalRunStorage evalRunStorage,
            EvalQualityGate qualityGate,
            EvalDimensionSummarizer dimensionSummarizer,
            ContextRuleBasedEvaluator contextEvaluator,
            ParserRuleBasedEvaluator parserEvaluator,
            @Value("${workbench.eval.judge.enabled:false}") boolean judgeEnabled,
            @Value("${workbench.eval.case-ids:}") String caseIds,
            @Value("${workbench.rag.top-k:5}") int ragTopK,
            @Value("${workbench.rag.similarity-threshold:1.0}") double similarityThreshold,
            @Value("${workbench.rag.score-direction:distance}") String scoreDirection,
            @Value("${workbench.rag.query-rewrite.enabled:false}") boolean queryRewriteEnabled,
            @Value("${workbench.rag.multi-query.enabled:false}") boolean multiQueryEnabled,
            @Value("${workbench.rag.debug:false}") boolean retrievalDebugEnabled
    ) {
        this.ragService = ragService;
        this.evaluator = evaluator;
        this.llmJudgeEvaluator = llmJudgeEvaluator;
        this.objectMapper = objectMapper;
        this.evalRunStorage = evalRunStorage;
        this.qualityGate = qualityGate;
        this.dimensionSummarizer = dimensionSummarizer;
        this.contextEvaluator = contextEvaluator;
        this.parserEvaluator = parserEvaluator;
        this.judgeEnabled = judgeEnabled;
        this.caseIds = parseCaseIds(caseIds);
        this.ragTopK = ragTopK;
        this.similarityThreshold = similarityThreshold;
        this.scoreDirection = scoreDirection;
        this.queryRewriteEnabled = queryRewriteEnabled;
        this.multiQueryEnabled = multiQueryEnabled;
        this.retrievalDebugEnabled = retrievalDebugEnabled;
    }

    public EvalSummary run() throws IOException {
        EvalSummary summary = run(readCases().stream().filter(this::shouldRun).toList(), false, null);
        writeJson(summary);
        writeReport(summary);
        return summary;
    }

    public EvalSummary run(List<EvalCase> cases, boolean enhanced, AppUser owner) throws IOException {
        String runId = RUN_ID_FORMAT.format(Instant.now());
        List<EvalResult> results = cases.stream()
                .map(evalCase -> runCase(evalCase, enhanced, owner == null ? "" : owner.getId().toString()))
                .toList();
        int passed = (int) results.stream().filter(EvalResult::passed).count();
        List<EvalResult> rankingResults = results.stream().filter(EvalResult::rankingMetricsApplicable).toList();
        List<EvalResult> keyPointResults = results.stream()
                .filter(result -> result.matchedKeywords() != null && result.missingKeywords() != null)
                .filter(result -> !result.matchedKeywords().isEmpty() || !result.missingKeywords().isEmpty())
                .toList();
        List<EvalResult> answerResults = results.stream().filter(result -> !result.expectNoAnswer()).toList();
        double passRate = results.isEmpty() ? 0 : (double) passed / results.size();
        double unsupportedAnswerRate = rate(answerResults, EvalResult::unsupportedAnswer);
        double recallAt5 = average(rankingResults, EvalResult::recallAt5);
        double precisionAt5 = average(rankingResults, EvalResult::precisionAt5);
        double mrr = average(rankingResults, EvalResult::reciprocalRank);
        double ndcgAt5 = average(rankingResults, EvalResult::ndcgAt5);
        EvalQualityGate.Result gate = qualityGate.evaluate(
                passRate, rankingResults.size(), recallAt5, mrr, unsupportedAnswerRate);
        EvalSummary summary = new EvalSummary(
                runId,
                results.size(),
                passed,
                results.size() - passed,
                passRate,
                rate(rankingResults, EvalResult::retrievalHit),
                rate(rankingResults, EvalResult::citationCorrect),
                average(keyPointResults, EvalResult::keyPointCoverage),
                unsupportedAnswerRate,
                rate(results, EvalResult::modelFallbackUsed),
                refusalCorrectnessRate(results),
                rankingResults.size(),
                recallAt5,
                precisionAt5,
                mrr,
                ndcgAt5,
                gate.enabled(),
                gate.passed(),
                gate.failures(),
                dimensionSummarizer.summarize(results),
                results
        );

        return evalRunStorage.save(owner, summary, enhanced, judgeEnabled);
    }

    private List<EvalCase> readCases() throws IOException {
        return EvalQuestionSource.readLines().stream()
                .filter(line -> !line.isBlank())
                .map(this::readCase)
                .toList();
    }

    private EvalCase readCase(String line) {
        try {
            return objectMapper.readValue(line, EvalCase.class);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to read eval case: " + line, exception);
        }
    }

    private EvalResult runCase(EvalCase evalCase, boolean enhanced, String ownerUserId) {
        if (evalCase.layer() == EvalLayer.CONTEXT) {
            return contextEvaluator.evaluate(evalCase, ragService.evaluateContext(
                    new com.example.workbench.rag.ContextEvaluationRequest(
                            "eval-" + evalCase.id(), evalCase.question(), evalCase.history(),
                            new RagChatOptions(false, false))));
        }
        if (evalCase.layer() == EvalLayer.PARSER) {
            return parserEvaluator.evaluate(evalCase);
        }
        String conversationId = ownerUserId == null || ownerUserId.isBlank()
                ? "eval-" + evalCase.id()
                : "user-" + ownerUserId + ":eval-" + evalCase.id();
        RagChatResponse response = ragService.chatForEvaluation(new RagChatRequest(conversationId, evalCase.question()),
                new RagChatOptions(enhanced || queryRewriteEnabled, enhanced || multiQueryEnabled));
        EvalResult ruleResult = evaluator.evaluate(evalCase, response);

        if (!judgeEnabled) {
            return ruleResult;
        }

        if (evalCase.expectNoAnswer()) {
            return withJudgeFallback(ruleResult, "Judge skipped for deterministic no-answer case");
        }

        JudgeResult judgeResult = llmJudgeEvaluator.evaluate(
                evalCase,
                ruleResult.answer(),
                ruleResult.actualSources(),
                ruleResult.actualHeadingPaths()
        );
        if (!judgeResult.available()) {
            return withJudgeFallback(ruleResult, judgeResult.reason());
        }

        double judgeNormalized = judgeResult.score() / 5.0;
        double finalScore = ruleResult.ruleScore() * 0.6 + judgeNormalized * 0.4;
        boolean passed = ruleResult.passed() && judgeResult.passed() && finalScore >= 0.75;

        return new EvalResult(
                ruleResult.id(),
                ruleResult.mode(),
                ruleResult.type(),
                ruleResult.question(),
                ruleResult.expectNoAnswer(),
                ruleResult.requireLocalEvidence(),
                ruleResult.allowModelFallback(),
                passed,
                ruleResult.ruleScore(),
                judgeResult.score(),
                judgeResult.passed(),
                finalScore,
                ruleResult.reason(),
                judgeResult.reason(),
                ruleResult.answer(),
                ruleResult.expectedSources(),
                ruleResult.actualSources(),
                ruleResult.expectedHeadingPaths(),
                ruleResult.actualHeadingPaths(),
                ruleResult.matchedKeywords(),
                ruleResult.missingKeywords(),
                ruleResult.retrievalHit(),
                ruleResult.citationCorrect(),
                ruleResult.keyPointCoverage(),
                ruleResult.unsupportedAnswer(),
                ruleResult.modelFallbackUsed(),
                ruleResult.refusalCorrect(),
                ruleResult.retrievalDebug(),
                ruleResult.suite(),
                ruleResult.layer(),
                ruleResult.rankingMetricsApplicable(),
                ruleResult.recallAt5(),
                ruleResult.precisionAt5(),
                ruleResult.reciprocalRank(),
                ruleResult.ndcgAt5(),
                ruleResult.firstRelevantRank()
        );
    }

    private EvalResult withJudgeFallback(EvalResult ruleResult, String judgeReason) {
        return new EvalResult(
                ruleResult.id(),
                ruleResult.mode(),
                ruleResult.type(),
                ruleResult.question(),
                ruleResult.expectNoAnswer(),
                ruleResult.requireLocalEvidence(),
                ruleResult.allowModelFallback(),
                ruleResult.passed(),
                ruleResult.ruleScore(),
                null,
                true,
                ruleResult.finalScore(),
                ruleResult.reason(),
                judgeReason,
                ruleResult.answer(),
                ruleResult.expectedSources(),
                ruleResult.actualSources(),
                ruleResult.expectedHeadingPaths(),
                ruleResult.actualHeadingPaths(),
                ruleResult.matchedKeywords(),
                ruleResult.missingKeywords(),
                ruleResult.retrievalHit(),
                ruleResult.citationCorrect(),
                ruleResult.keyPointCoverage(),
                ruleResult.unsupportedAnswer(),
                ruleResult.modelFallbackUsed(),
                ruleResult.refusalCorrect(),
                ruleResult.retrievalDebug(),
                ruleResult.suite(),
                ruleResult.layer(),
                ruleResult.rankingMetricsApplicable(),
                ruleResult.recallAt5(),
                ruleResult.precisionAt5(),
                ruleResult.reciprocalRank(),
                ruleResult.ndcgAt5(),
                ruleResult.firstRelevantRank()
        );
    }

    private void writeJson(EvalSummary summary) throws IOException {
        Files.createDirectories(RESULTS_PATH.getParent());
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(RESULTS_PATH.toFile(), summary);
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(historyPath(RESULTS_PATH, summary.runId()).toFile(), summary);
    }

    private void writeReport(EvalSummary summary) throws IOException {
        Files.createDirectories(REPORT_PATH.getParent());
        String report = markdownReport(summary);
        Files.writeString(REPORT_PATH, report, StandardCharsets.UTF_8);
        Files.writeString(historyPath(REPORT_PATH, summary.runId()), report, StandardCharsets.UTF_8);
    }

    private String markdownReport(EvalSummary summary) {
        StringBuilder builder = new StringBuilder();
        builder.append("# Eval Report\n\n");
        builder.append("Run ID: ").append(summary.runId()).append("\n");
        builder.append("Total: ").append(summary.total()).append("\n");
        builder.append("Passed: ").append(summary.passed()).append("\n");
        builder.append("Failed: ").append(summary.failed()).append("\n");
        builder.append("Pass Rate: ").append(String.format("%.0f%%", summary.passRate() * 100)).append("\n\n");
        builder.append("Judge Enabled: ").append(judgeEnabled).append("\n\n");
        builder.append("## Quality Metrics\n\n");
        builder.append("Retrieval Hit Rate: ").append(percent(summary.retrievalHitRate())).append("\n");
        builder.append("Citation Correctness Rate: ").append(percent(summary.citationCorrectnessRate())).append("\n");
        builder.append("Key Point Coverage Rate: ").append(percent(summary.keyPointCoverageRate())).append("\n");
        builder.append("Unsupported Answer Rate: ").append(percent(summary.unsupportedAnswerRate())).append("\n");
        builder.append("Model Fallback Rate: ").append(percent(summary.modelFallbackRate())).append("\n");
        builder.append("Refusal Correctness Rate: ").append(percent(summary.refusalCorrectnessRate())).append("\n\n");
        builder.append("Ranking Cases: ").append(summary.rankingCaseCount()).append("\n");
        builder.append("Recall@5: ").append(percent(summary.recallAt5())).append("\n");
        builder.append("Precision@5: ").append(percent(summary.precisionAt5())).append("\n");
        builder.append("MRR: ").append(String.format("%.3f", summary.mrr())).append("\n");
        builder.append("NDCG@5: ").append(String.format("%.3f", summary.ndcgAt5())).append("\n");
        builder.append("Quality Gate: ").append(summary.gateEnabled() ? summary.gatePassed() : "disabled").append("\n");
        if (!summary.gateFailures().isEmpty()) {
            builder.append("Gate Failures: ").append(String.join("; ", summary.gateFailures())).append("\n");
        }
        builder.append("\n");
        builder.append("## Results By Suite / Layer\n\n");
        builder.append("| Suite | Layer | Total | Passed | Pass Rate | Recall@5 | MRR |\n|---|---|---:|---:|---:|---:|---:|\n");
        for (EvalDimensionSummary dimension : summary.dimensionSummaries()) {
            builder.append("| ").append(dimension.suite()).append(" | ").append(dimension.layer())
                    .append(" | ").append(dimension.total()).append(" | ").append(dimension.passed())
                    .append(" | ").append(percent(dimension.passRate()))
                    .append(" | ").append(percent(dimension.recallAt5()))
                    .append(" | ").append(String.format("%.3f", dimension.mrr())).append(" |\n");
        }
        builder.append("\n");
        appendConfigSnapshot(builder);
        builder.append("## Results By Type\n\n");
        builder.append("| Type | Total | Passed | Pass Rate |\n");
        builder.append("|---|---:|---:|---:|\n");

        for (TypeStats stats : statsByType(summary).values()) {
            builder.append("| ")
                    .append(stats.type())
                    .append(" | ")
                    .append(stats.total())
                    .append(" | ")
                    .append(stats.passed())
                    .append(" | ")
                    .append(String.format("%.0f%%", stats.passRate() * 100))
                    .append(" |\n");
        }

        builder.append("\n");
        builder.append("## Summary\n\n");
        builder.append("| ID | Type | Rule | Judge | Final | Passed | Reason |\n");
        builder.append("|---|---|---:|---:|---:|---:|---|\n");

        for (EvalResult result : summary.results()) {
            builder.append("| ")
                    .append(result.id())
                    .append(" | ")
                    .append(result.type())
                    .append(" | ")
                    .append(String.format("%.2f", result.ruleScore()))
                    .append(" | ")
                    .append(result.judgeScore() == null ? "-" : result.judgeScore())
                    .append(" | ")
                    .append(String.format("%.2f", result.finalScore()))
                    .append(" | ")
                    .append(result.passed())
                    .append(" | ")
                    .append(result.reason().replace("|", "\\|"))
                    .append(" |\n");
        }

        appendJudgeFindings(builder, summary);

        builder.append("\n## Failed Cases\n");

        List<EvalResult> failedResults = summary.results().stream()
                .filter(result -> !result.passed())
                .toList();

        if (failedResults.isEmpty()) {
            builder.append("\nNone\n");
        }

        for (EvalResult result : failedResults) {
            builder.append("\n### ").append(result.id()).append("\n\n");
            appendListBlock(builder, "Question", List.of(result.question()));
            appendListBlock(builder, "Type", List.of(result.type()));
            appendListBlock(builder, "Rule Score", List.of(String.format("%.2f", result.ruleScore())));
            appendListBlock(builder, "Judge Score", List.of(result.judgeScore() == null ? "-" : result.judgeScore().toString()));
            appendListBlock(builder, "Final Score", List.of(String.format("%.2f", result.finalScore())));
            appendListBlock(builder, "Judge Reason", List.of(result.judgeReason()));
            appendListBlock(builder, "Expected Sources", result.expectedSources());
            appendListBlock(builder, "Actual Sources", result.actualSources());
            appendListBlock(builder, "Expected Heading Paths", result.expectedHeadingPaths());
            appendListBlock(builder, "Actual Heading Paths", result.actualHeadingPaths());
            appendListBlock(builder, "Missing Keywords", result.missingKeywords());
            appendListBlock(builder, "Answer", List.of(result.answer()));
        }

        return builder.toString();
    }

    private void appendConfigSnapshot(StringBuilder builder) {
        builder.append("## Config\n\n");
        builder.append("topK: ").append(ragTopK).append("\n");
        builder.append("similarityThreshold: ").append(similarityThreshold).append("\n");
        builder.append("scoreDirection: ").append(scoreDirection).append("\n");
        builder.append("queryRewrite: ").append(queryRewriteEnabled).append("\n");
        builder.append("multiQuery: ").append(multiQueryEnabled).append("\n");
        builder.append("rerank: enabled (规则重排：阈值过滤后，按文档类别、标题路径和关键词匹配提升候选排序；不使用通用 reranker)\n");
        builder.append("retrievalDebug: ").append(retrievalDebugEnabled).append("\n\n");
    }

    private void appendJudgeFindings(StringBuilder builder, EvalSummary summary) {
        builder.append("\n## Judge Findings\n");

        if (!judgeEnabled) {
            builder.append("\nJudge disabled\n");
            return;
        }

        List<EvalResult> findings = summary.results().stream()
                .filter(result -> result.judgeScore() != null)
                .filter(result -> result.judgeScore() < 5 || !result.judgePassed())
                .toList();

        if (findings.isEmpty()) {
            builder.append("\nNone\n");
            return;
        }

        for (EvalResult result : findings) {
            builder.append("\n### ").append(result.id()).append("\n\n");
            appendListBlock(builder, "Judge Score", List.of(result.judgeScore().toString()));
            appendListBlock(builder, "Judge Passed", List.of(Boolean.toString(result.judgePassed())));
            appendListBlock(builder, "Judge Reason", List.of(result.judgeReason()));
        }
    }

    private boolean shouldRun(EvalCase evalCase) {
        return caseIds.isEmpty() || caseIds.contains(evalCase.id());
    }

    private Set<String> parseCaseIds(String value) {
        if (value == null || value.isBlank()) {
            return Set.of();
        }

        return List.of(value.split(",")).stream()
                .map(String::trim)
                .filter(item -> !item.isBlank())
                .collect(Collectors.toSet());
    }

    private Map<String, TypeStats> statsByType(EvalSummary summary) {
        return summary.results().stream()
                .collect(Collectors.groupingBy(
                        result -> result.type() == null || result.type().isBlank() ? "unknown" : result.type(),
                        TreeMap::new,
                        Collectors.collectingAndThen(Collectors.toList(), this::typeStats)
                ));
    }

    private TypeStats typeStats(List<EvalResult> results) {
        int passed = (int) results.stream().filter(EvalResult::passed).count();
        return new TypeStats(
                results.get(0).type() == null || results.get(0).type().isBlank() ? "unknown" : results.get(0).type(),
                results.size(),
                passed,
                results.isEmpty() ? 0 : (double) passed / results.size()
        );
    }

    private void appendListBlock(StringBuilder builder, String title, List<String> values) {
        builder.append(title).append(":\n");

        if (values == null || values.isEmpty()) {
            builder.append("None\n\n");
            return;
        }

        builder.append(String.join(", ", values)).append("\n\n");
    }

    private Path historyPath(Path latestPath, String runId) {
        String fileName = latestPath.getFileName().toString();
        String extension = fileName.substring(fileName.lastIndexOf('.'));
        return latestPath.resolveSibling(runId + extension);
    }

    private double rate(List<EvalResult> results, java.util.function.Predicate<EvalResult> predicate) {
        return results.isEmpty() ? 0 : (double) results.stream().filter(predicate).count() / results.size();
    }

    private double average(List<EvalResult> results, java.util.function.ToDoubleFunction<EvalResult> value) {
        return results.isEmpty() ? 0 : results.stream().mapToDouble(value).average().orElse(0);
    }

    private double refusalCorrectnessRate(List<EvalResult> results) {
        List<EvalResult> refusalCases = results.stream().filter(EvalResult::expectNoAnswer).toList();
        return rate(refusalCases, EvalResult::refusalCorrect);
    }

    private String percent(double value) {
        return String.format("%.0f%%", value * 100);
    }

    private record TypeStats(String type, int total, int passed, double passRate) {
    }
}
