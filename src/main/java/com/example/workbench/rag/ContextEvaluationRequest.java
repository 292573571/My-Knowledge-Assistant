package com.example.workbench.rag;

import java.util.List;
import com.example.workbench.memory.ChatMessage;

/** 上下文层评测请求，不触发向量检索和回答生成。 */
public record ContextEvaluationRequest(
        String conversationId,
        String currentQuestion,
        List<ChatMessage> history,
        RagChatOptions options
) {
    public ContextEvaluationRequest {
        history = history == null ? List.of() : List.copyOf(history);
        options = options == null ? new RagChatOptions(false, false) : options;
    }
}
