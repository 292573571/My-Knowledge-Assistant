package com.example.workbench.learningassistant;

import com.example.workbench.agent.TeachingUserLevel;
import jakarta.validation.constraints.Size;

public record LearningAssistantSessionRequest(
        String workspaceId,
        @Size(max = 120, message = "topic 不能超过 120 个字符") String topic,
        LearningMode mode,
        TeachingUserLevel userLevel
) {
}
