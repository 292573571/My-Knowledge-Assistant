package com.example.workbench.learningassistant;

import com.example.workbench.agent.TeachingSessionSummary;
import com.example.workbench.agent.TeachingCheckPrompt;
import com.example.workbench.agent.TeachingPracticePrompt;
import com.example.workbench.conversation.MessageResponse;
import java.time.Instant;
import java.util.List;

public record LearningAssistantSessionResponse(
        String sessionId,
        String title,
        String workspaceId,
        LearningMode mode,
        String topic,
        String userLevel,
        TeachingSessionSummary progress,
        TeachingCheckPrompt pendingCheck,
        TeachingPracticePrompt pendingPractice,
        List<MessageResponse> messages,
        Instant updatedAt
) {
}
