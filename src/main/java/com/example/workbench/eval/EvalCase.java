package com.example.workbench.eval;

import java.util.List;
import com.example.workbench.memory.ChatMessage;
import com.example.workbench.rag.ContextRelation;

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
        List<String> forbiddenRetrievalKeywords,
        EvalSuite suite,
        EvalLayer layer,
        List<ChatMessage> history,
        ContextRelation expectedRelation,
        String expectedStandaloneQuestion,
        List<String> expectedRetrievalQueries
) {
    public EvalCase {
        suite = suite == null ? EvalSuite.REGRESSION : suite;
        layer = layer == null ? EvalLayer.GENERATION : layer;
        history = history == null ? List.of() : List.copyOf(history);
        expectedRetrievalQueries = expectedRetrievalQueries == null ? List.of() : List.copyOf(expectedRetrievalQueries);
    }

    public EvalCase(
            String id, String mode, String type, String question,
            boolean expectNoAnswer, boolean requireLocalEvidence, boolean allowModelFallback,
            List<String> expectedSources, List<String> expectedHeadingPaths,
            List<String> expectedKeywords, List<String> forbiddenKeywords,
            List<Integer> expectedPageNumbers, List<String> expectedRetrievalKeywords,
            List<String> forbiddenRetrievalKeywords
    ) {
        this(id, mode, type, question, expectNoAnswer, requireLocalEvidence, allowModelFallback,
                expectedSources, expectedHeadingPaths, expectedKeywords, forbiddenKeywords,
                expectedPageNumbers, expectedRetrievalKeywords, forbiddenRetrievalKeywords,
                EvalSuite.REGRESSION, EvalLayer.GENERATION, List.of(), null, null, List.of());
    }

    public EvalCase(
            String id, String mode, String type, String question,
            boolean expectNoAnswer, boolean requireLocalEvidence, boolean allowModelFallback,
            List<String> expectedSources, List<String> expectedHeadingPaths,
            List<String> expectedKeywords, List<String> forbiddenKeywords
    ) {
        this(id, mode, type, question, expectNoAnswer, requireLocalEvidence, allowModelFallback,
                expectedSources, expectedHeadingPaths, expectedKeywords, forbiddenKeywords,
                List.of(), List.of(), List.of(), EvalSuite.REGRESSION, EvalLayer.GENERATION, List.of(), null, null, List.of());
    }
}
