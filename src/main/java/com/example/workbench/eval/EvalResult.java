package com.example.workbench.eval;

import com.example.workbench.rag.RetrievalDebug;
import java.util.List;

public record EvalResult(
        String id,
        String mode,
        String type,
        String question,
        boolean expectNoAnswer,
        boolean requireLocalEvidence,
        boolean allowModelFallback,
        boolean passed,
        double ruleScore,
        Integer judgeScore,
        boolean judgePassed,
        double finalScore,
        String reason,
        String judgeReason,
        String answer,
        List<String> expectedSources,
        List<String> actualSources,
        List<String> expectedHeadingPaths,
        List<String> actualHeadingPaths,
        List<String> matchedKeywords,
        List<String> missingKeywords,
        boolean retrievalHit,
        boolean citationCorrect,
        double keyPointCoverage,
        boolean unsupportedAnswer,
        boolean modelFallbackUsed,
        boolean refusalCorrect,
        List<RetrievalDebug> retrievalDebug,
        EvalSuite suite,
        EvalLayer layer,
        boolean rankingMetricsApplicable,
        double recallAt5,
        double precisionAt5,
        double reciprocalRank,
        double ndcgAt5,
        Integer firstRelevantRank
) {
    public EvalResult {
        suite = suite == null ? EvalSuite.REGRESSION : suite;
        layer = layer == null ? EvalLayer.GENERATION : layer;
    }
}
