package com.example.workbench.learningassistant;

import com.example.workbench.agent.TeachingSessionSummary;
import com.example.workbench.conversation.MessageResponse;
import java.time.Instant;
import java.util.List;

public record LearningAssistantSessionResponse(
        String sessionId,
        String title,
        String workspaceId,
        LearningMode mode,
        String topic,
        TeachingSessionSummary progress,
        List<MessageResponse> messages,
        Instant updatedAt
) {
}
