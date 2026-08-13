package com.example.workbench.learning;

import java.time.Instant;
import java.time.LocalDate;

public record LearningRecordEntry(
        String id,
        Long ownerUserId,
        String workspaceId,
        LocalDate recordDate,
        LearningRecordType type,
        String question,
        String answer,
        String topic,
        String sessionId,
        String conversationId,
        Long messageId,
        String attemptId,
        String practiceId,
        Integer score,
        Integer maxScore,
        Boolean passed,
        String feedback,
        String weakPoint,
        String reviewExplanation,
        String reviewSuggestion,
        String sourcesJson,
        String markdown,
        String sourceKey,
        boolean legacy,
        Instant createdAt,
        Instant updatedAt
) {

    public static LearningRecordEntry teachingExplanation(String id, Long ownerUserId, String workspaceId,
                                                          LocalDate recordDate, String sessionId, String topic,
                                                          String explanation, String sourcesJson, String markdown,
                                                          String sourceKey, Instant createdAt, Instant updatedAt) {
        return new LearningRecordEntry(id, ownerUserId, workspaceId, recordDate,
                LearningRecordType.TEACHING_EXPLANATION, null, explanation, topic, sessionId, null, null,
                null, null, null, null, null, null, null, null, null, sourcesJson, markdown,
                sourceKey, false, createdAt, updatedAt);
    }

    public LearningRecordEntry(String id, Long ownerUserId, String workspaceId, LocalDate recordDate,
                               LearningRecordType type, String question, String answer, String topic,
                               String attemptId, String practiceId, Integer score, Integer maxScore, Boolean passed,
                               String feedback, String weakPoint, String reviewExplanation, String reviewSuggestion,
                               String sourcesJson, String markdown, String sourceKey, boolean legacy,
                               Instant createdAt, Instant updatedAt) {
        this(id, ownerUserId, workspaceId, recordDate, type, question, answer, topic, null, null, null,
                attemptId, practiceId, score, maxScore, passed, feedback, weakPoint, reviewExplanation,
                reviewSuggestion, sourcesJson, markdown, sourceKey, legacy, createdAt, updatedAt);
    }
}
