package com.example.workbench.agent;

import java.util.List;

public record TeachingSessionSummary(
        String lessonId,
        String sessionId,
        TeachingSessionStatus status,
        TeachingNextAction nextAction,
        Integer checkScore,
        int checkMaxScore,
        boolean checkCompleted,
        boolean checkPassed,
        Integer practiceScore,
        int practiceMaxScore,
        boolean practiceCompleted,
        boolean practicePassed,
        int score,
        int maxScore,
        int masteryPercent,
        int completedItems,
        int requiredItems,
        List<String> weakPoints
) {
}
