package com.example.workbench.workbench;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record WorkbenchChatRequest(
        @Pattern(regexp = "[A-Za-z0-9-]{1,64}", message = "invalid conversationId") String conversationId,
        String mode,
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

    public String normalizedMode() { return "rag"; }

    public WorkbenchChatRequest(String conversationId, String mode, String message) {
        this(conversationId, mode, null, message);
    }
}
