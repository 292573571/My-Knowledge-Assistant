package com.example.workbench.eval;

import com.example.workbench.rag.RetrievalDebug;
import java.util.List;

public record EvalResult(
        String id,
        String mode,
        String type,
        String question,
        boolean expectNoAnswer,
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
        List<RetrievalDebug> retrievalDebug
) {
}
