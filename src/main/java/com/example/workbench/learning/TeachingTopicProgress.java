package com.example.workbench.learning;

public record TeachingTopicProgress(
        String topic,
        int attempts,
        int passedAttempts,
        int bestScore,
        int maxScore,
        int latestScore,
        boolean latestPassed,
        String latestDate,
        int masteryPercent
) {
}
