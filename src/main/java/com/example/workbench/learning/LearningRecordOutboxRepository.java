package com.example.workbench.learning;

import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

interface LearningRecordOutboxRepository extends JpaRepository<LearningRecordOutboxEntity, String> {

    @Query(value = """
            select * from learning_record_outbox
             where (status = 'QUEUED' and available_at <= :now)
                or (status = 'PROCESSING' and lease_expires_at <= :now)
             order by created_at asc
             limit 1
             for update skip locked
            """, nativeQuery = true)
    Optional<LearningRecordOutboxEntity> findNextForUpdate(Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("""
            update LearningRecordOutboxEntity event
               set event.leaseExpiresAt = :expiresAt
             where event.id = :id and event.status = :processing
               and event.leaseOwner = :owner and event.generation = :generation
            """)
    int renewLease(@Param("id") String id, @Param("processing") LearningRecordOutboxEntity.Status processing,
                   @Param("owner") String owner, @Param("generation") long generation,
                   @Param("expiresAt") Instant expiresAt);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("""
            update LearningRecordOutboxEntity event
               set event.status = :done, event.leaseOwner = null,
                   event.leaseExpiresAt = null, event.processedAt = :processedAt,
                   event.lastError = null
             where event.id = :id and event.status = :processing
               and event.leaseOwner = :owner and event.generation = :generation
            """)
    int done(@Param("id") String id, @Param("processing") LearningRecordOutboxEntity.Status processing,
             @Param("done") LearningRecordOutboxEntity.Status done, @Param("owner") String owner,
             @Param("generation") long generation, @Param("processedAt") Instant processedAt);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("""
            update LearningRecordOutboxEntity event
               set event.status = :queued, event.availableAt = :availableAt,
                   event.leaseOwner = null, event.leaseExpiresAt = null,
                   event.lastError = :lastError
             where event.id = :id and event.status = :processing
               and event.leaseOwner = :owner and event.generation = :generation
            """)
    int retry(@Param("id") String id, @Param("processing") LearningRecordOutboxEntity.Status processing,
              @Param("queued") LearningRecordOutboxEntity.Status queued, @Param("owner") String owner,
              @Param("generation") long generation, @Param("availableAt") Instant availableAt,
              @Param("lastError") String lastError);
}
