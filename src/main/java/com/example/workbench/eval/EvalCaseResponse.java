package com.example.workbench.eval;

import java.util.List;
import com.example.workbench.memory.ChatMessage;
import com.example.workbench.rag.ContextRelation;

public record EvalCaseResponse(
        Long id, String caseId, String mode, String type, String question,
        boolean expectNoAnswer, boolean requireLocalEvidence, boolean allowModelFallback,
        List<String> expectedSources, List<String> expectedHeadingPaths,
        List<String> expectedKeywords, List<String> forbiddenKeywords,
        List<Integer> expectedPageNumbers, List<String> expectedRetrievalKeywords,
        List<String> forbiddenRetrievalKeywords, EvalSuite suite, EvalLayer layer,
        List<ChatMessage> history, ContextRelation expectedRelation, String expectedStandaloneQuestion,
        List<String> expectedRetrievalQueries
) {
}
