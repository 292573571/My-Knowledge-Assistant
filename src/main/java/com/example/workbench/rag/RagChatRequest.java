package com.example.workbench.rag;

import jakarta.validation.constraints.NotBlank;

public record RagChatRequest(
        String conversationId,
        String workspaceId,
        @NotBlank(message = "message cannot be empty")
        String message
) {

    public String normalizedConversationId() {
        if (conversationId == null || conversationId.isBlank()) {
            return "default";
        }

        return conversationId;
    }

    public RagChatRequest(String conversationId, String message) {
        this(conversationId, null, message);
    }
}
