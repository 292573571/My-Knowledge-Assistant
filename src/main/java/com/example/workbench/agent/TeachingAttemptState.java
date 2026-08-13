package com.example.workbench.agent;

import java.time.Instant;

final class TeachingAttemptState {

    final String checkId;
    final String ownerKey;
    final String workspaceId;
    final String sessionId;
    final String topic;
    final String question;
    final Instant expiresAt;
    final Instant createdAt;
    String answer = "";
    boolean checkCompleted;
    TeachingCheckResponse response;
    String practiceId;
    String practiceQuestion;
    String practiceAnswer = "";
    boolean practiceCompleted;
    TeachingPracticeResponse practiceResponse;

    TeachingAttemptState(String checkId, String ownerKey, String workspaceId, String sessionId,
                         String topic, String question, Instant expiresAt, Instant createdAt) {
        this.checkId = checkId;
        this.ownerKey = ownerKey;
        this.workspaceId = workspaceId;
        this.sessionId = sessionId;
        this.topic = topic;
        this.question = question;
        this.expiresAt = expiresAt;
        this.createdAt = createdAt;
    }
}
