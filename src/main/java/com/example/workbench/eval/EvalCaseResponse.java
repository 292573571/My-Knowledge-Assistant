package com.example.workbench.eval;

import java.util.List;

public record EvalCaseResponse(
        Long id, String caseId, String mode, String type, String question,
        boolean expectNoAnswer, boolean requireLocalEvidence, boolean allowModelFallback,
        List<String> expectedSources, List<String> expectedHeadingPaths,
        List<String> expectedKeywords, List<String> forbiddenKeywords
) {
}
