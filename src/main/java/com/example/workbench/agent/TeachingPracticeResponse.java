package com.example.workbench.agent;

public record TeachingPracticeResponse(
        String practiceId,
        String sessionId,
        String topic,
        String question,
        TeachingPracticeStatus status,
        TeachingStage stage,
        TeachingNextAction nextAction,
        int score,
        int maxScore,
        boolean passed,
        String feedback,
        TeachingReview review,
        TeachingSessionSummary sessionSummary,
        boolean saved,
        String recordDate,
        boolean readOnly
) {
}
