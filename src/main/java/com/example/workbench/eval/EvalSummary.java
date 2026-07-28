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
        List<EvalResult> results
) {
}
