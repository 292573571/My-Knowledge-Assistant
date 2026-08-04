package com.example.workbench.eval;

import java.util.List;

public record EvalCase(
        String id,
        String mode,
        String type,
        String question,
        boolean expectNoAnswer,
        boolean requireLocalEvidence,
        boolean allowModelFallback,
        List<String> expectedSources,
        List<String> expectedHeadingPaths,
        List<String> expectedKeywords,
        List<String> forbiddenKeywords,
        List<Integer> expectedPageNumbers,
        List<String> expectedRetrievalKeywords,
        List<String> forbiddenRetrievalKeywords
) {
    public EvalCase(
            String id, String mode, String type, String question,
            boolean expectNoAnswer, boolean requireLocalEvidence, boolean allowModelFallback,
            List<String> expectedSources, List<String> expectedHeadingPaths,
            List<String> expectedKeywords, List<String> forbiddenKeywords
    ) {
        this(id, mode, type, question, expectNoAnswer, requireLocalEvidence, allowModelFallback,
                expectedSources, expectedHeadingPaths, expectedKeywords, forbiddenKeywords,
                List.of(), List.of(), List.of());
    }
}
