package com.example.workbench.agent;

public record TeachingPendingActions(
        TeachingCheckPrompt check,
        TeachingPracticePrompt practice
) {
    public static TeachingPendingActions empty() {
        return new TeachingPendingActions(null, null);
    }
}
