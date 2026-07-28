package com.example.workbench.eval;

import java.util.List;

public record EvalCase(
        String id,
        String mode,
        String type,
        String question,
        boolean expectNoAnswer,
        List<String> expectedSources,
        List<String> expectedHeadingPaths,
        List<String> expectedKeywords,
        List<String> forbiddenKeywords
) {
}
