package com.example.workbench.rag;

import jakarta.validation.constraints.NotBlank;

public record RagChatRequest(
        String conversationId,
        String workspaceId,
        String clientConversationId,
        @NotBlank(message = "message cannot be empty")
        String message
) {

    public String normalizedConversationId() {
        if (conversationId == null || conversationId.isBlank()) {
            return "default";
        }

        return conversationId;
    }

    public String normalizedClientConversationId() {
        if (clientConversationId != null && !clientConversationId.isBlank()) {
            return clientConversationId.strip();
        }
        return normalizedConversationId();
    }

    public RagChatRequest(String conversationId, String message) {
        this(conversationId, null, null, message);
    }

    public RagChatRequest(String conversationId, String workspaceId, String message) {
        this(conversationId, workspaceId, null, message);
    }
}
