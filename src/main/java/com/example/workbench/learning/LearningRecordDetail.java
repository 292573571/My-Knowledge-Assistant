package com.example.workbench.learning;

import java.time.Instant;

public record LearningRecordDetail(
        String date,
        String title,
        String content,
        Instant updatedAt
) {
}
