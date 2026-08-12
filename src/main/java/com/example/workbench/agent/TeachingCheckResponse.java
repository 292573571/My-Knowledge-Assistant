package com.example.workbench.agent;

public record TeachingCheckResponse(
        String attemptId,
        String sessionId,
        String topic,
        TeachingStage stage,
        TeachingNextAction nextAction,
        int score,
        int maxScore,
        boolean passed,
        String feedback,
        boolean saved,
        String recordDate,
        boolean readOnly
) {
}
