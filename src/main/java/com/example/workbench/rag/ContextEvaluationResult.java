package com.example.workbench.rag;

import com.example.workbench.memory.ChatMessage;
import java.util.List;

/** 上下文层评测结果。 */
public record ContextEvaluationResult(
        ContextRelation relation,
        String standaloneQuestion,
        List<String> retrievalQueries,
        List<ChatMessage> relevantHistory
) {
    public ContextEvaluationResult {
        retrievalQueries = retrievalQueries == null ? List.of() : List.copyOf(retrievalQueries);
        relevantHistory = relevantHistory == null ? List.of() : List.copyOf(relevantHistory);
    }
}
