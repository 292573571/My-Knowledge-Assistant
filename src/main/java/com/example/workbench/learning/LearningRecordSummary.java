package com.example.workbench.learning;

import java.time.Instant;

public record LearningRecordSummary(
        String date,
        String title,
        Instant updatedAt
) {
}
