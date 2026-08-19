package com.example.workbench.learningassistant;

import java.util.Optional;
import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LearningSessionEventRepository extends JpaRepository<LearningSessionEventEntity, String> {
    Optional<LearningSessionEventEntity> findBySessionIdAndEventTypeAndClientRequestId(
            String sessionId, String eventType, String clientRequestId);

    long deleteBySessionIdAndStatusAndProcessingExpiresAtBefore(
            String sessionId, String status, Instant expiresAt);

    long deleteBySessionIdAndStatus(String sessionId, String status);
}
