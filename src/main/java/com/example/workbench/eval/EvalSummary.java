package com.example.workbench.eval;

import java.util.List;

public record EvalSummary(
        String runId,
        int total,
        int passed,
        int failed,
        double passRate,
        double retrievalHitRate,
        double citationCorrectnessRate,
        double keyPointCoverageRate,
        double unsupportedAnswerRate,
        double modelFallbackRate,
        double refusalCorrectnessRate,
        int rankingCaseCount,
        double recallAt5,
        double precisionAt5,
        double mrr,
        double ndcgAt5,
        boolean gateEnabled,
        boolean gatePassed,
        List<String> gateFailures,
        List<EvalDimensionSummary> dimensionSummaries,
        List<EvalResult> results
) {
    public EvalSummary {
        gateFailures = gateFailures == null ? List.of() : List.copyOf(gateFailures);
        dimensionSummaries = dimensionSummaries == null ? List.of() : List.copyOf(dimensionSummaries);
        results = results == null ? List.of() : List.copyOf(results);
    }

    public EvalSummary(
            String runId, int total, int passed, int failed, double passRate,
            double retrievalHitRate, double citationCorrectnessRate, double keyPointCoverageRate,
            double unsupportedAnswerRate, double modelFallbackRate, double refusalCorrectnessRate,
            List<EvalResult> results
    ) {
        this(runId, total, passed, failed, passRate, retrievalHitRate, citationCorrectnessRate,
                keyPointCoverageRate, unsupportedAnswerRate, modelFallbackRate, refusalCorrectnessRate,
                0, 0, 0, 0, 0, false, true, List.of(), List.of(), results);
    }
}
