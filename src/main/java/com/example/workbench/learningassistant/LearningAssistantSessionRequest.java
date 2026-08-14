package com.example.workbench.learningassistant;

import com.example.workbench.agent.TeachingUserLevel;

public record LearningAssistantSessionRequest(
        String workspaceId,
        LearningMode mode,
        TeachingUserLevel userLevel
) {
}
