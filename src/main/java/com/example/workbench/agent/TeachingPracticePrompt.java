package com.example.workbench.agent;

import java.time.Instant;

public record TeachingPracticePrompt(
        String practiceId,
        String question,
        Instant expiresAt,
        TeachingPracticeStatus status
) {
}
