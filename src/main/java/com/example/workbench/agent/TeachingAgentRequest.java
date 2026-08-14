package com.example.workbench.agent;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record TeachingAgentRequest(
        String workspaceId,
        @Size(max = 64, message = "sessionId 不能超过 64 个字符") String sessionId,
        @Size(max = 120, message = "topic 不能超过 120 个字符") String topic,
        TeachingUserLevel userLevel,
        @NotBlank(message = "message 不能为空") @Size(max = 4000, message = "message 不能超过 4000 个字符") String message
) {
    public String normalizedSessionId() {
        return sessionId == null || sessionId.isBlank() ? "default" : sessionId.strip();
    }

    public TeachingUserLevel normalizedUserLevel() {
        return userLevel == null ? TeachingUserLevel.BEGINNER : userLevel;
    }
}
