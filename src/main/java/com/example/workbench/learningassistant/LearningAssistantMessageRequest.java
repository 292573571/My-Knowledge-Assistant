package com.example.workbench.learningassistant;

import com.example.workbench.agent.TeachingUserLevel;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LearningAssistantMessageRequest(
        @NotBlank(message = "workspaceId 不能为空") @Size(max = 120, message = "workspaceId 不能超过 120 个字符") String workspaceId,
        @NotBlank(message = "message 不能为空") @Size(max = 4000, message = "message 不能超过 4000 个字符") String message,
        @Size(max = 120, message = "topic 不能超过 120 个字符") String topic,
        LearningMode mode,
        LearningIntent intent,
        TeachingUserLevel userLevel,
        @NotBlank(message = "clientRequestId 不能为空") @Size(max = 100, message = "clientRequestId 不能超过 100 个字符") String clientRequestId
) {
    public LearningMode normalizedMode() {
        return mode == null ? LearningMode.AUTO : mode;
    }

    public TeachingUserLevel normalizedUserLevel() {
        return userLevel == null ? TeachingUserLevel.BEGINNER : userLevel;
    }
}
