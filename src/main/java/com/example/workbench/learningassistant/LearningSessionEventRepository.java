package com.example.workbench.learningassistant;

import java.util.Optional;
import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface LearningSessionEventRepository extends JpaRepository<LearningSessionEventEntity, String> {
    Optional<LearningSessionEventEntity> findBySessionIdAndEventTypeAndClientRequestId(
            String sessionId, String eventType, String clientRequestId);

    @Modifying
    @Transactional
    @Query("update LearningSessionEventEntity event set event.status = :status "
            + "where event.sessionId = :sessionId and event.status = 'PROCESSING' "
            + "and event.processingExpiresAt <= :expiresAt")
    int expireProcessing(@Param("sessionId") String sessionId, @Param("status") String status,
                         @Param("expiresAt") Instant expiresAt);

    @Deprecated
    default long deleteBySessionIdAndStatusAndProcessingExpiresAtBefore(String sessionId, String status,
                                                                          Instant expiresAt) {
        return expireProcessing(sessionId, "EXPIRED", expiresAt);
    }

    @Modifying
    @Transactional
    @Query("update LearningSessionEventEntity event set event.status = :status "
            + "where event.sessionId = :sessionId and event.status = 'PROCESSING'")
    int abandonProcessing(@Param("sessionId") String sessionId, @Param("status") String status);

    @Modifying
    @Transactional
    @Query("update LearningSessionEventEntity event set event.status = :status "
            + "where event.eventId = :eventId and event.status = 'PROCESSING' and event.generation = :generation")
    int abandon(@Param("eventId") String eventId, @Param("generation") long generation,
                @Param("status") String status);

    @Modifying
    @Transactional
    @Query("update LearningSessionEventEntity event set event.status = 'SUCCEEDED', "
            + "event.payloadJson = :payloadJson, event.processingExpiresAt = null "
            + "where event.eventId = :eventId and event.status = 'PROCESSING' "
            + "and event.generation = :generation")
    int succeed(@Param("eventId") String eventId, @Param("generation") long generation,
                @Param("payloadJson") String payloadJson);
}
