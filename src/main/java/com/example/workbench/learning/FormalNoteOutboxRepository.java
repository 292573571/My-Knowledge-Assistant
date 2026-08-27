package com.example.workbench.learning;

import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

interface FormalNoteOutboxRepository extends JpaRepository<FormalNoteOutboxEntity, String> {
    @Query(value = """
            select * from formal_note_outbox
             where (status = 'QUEUED' and available_at <= :now)
                or (status = 'PROCESSING' and lease_expires_at <= :now)
             order by created_at asc
             limit 1
             for update skip locked
            """, nativeQuery = true)
    Optional<FormalNoteOutboxEntity> findNextForUpdate(Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("""
            update FormalNoteOutboxEntity event
               set event.leaseExpiresAt = :expiresAt
             where event.id = :id and event.status = :processing
               and event.leaseOwner = :owner and event.generation = :generation
            """)
    int renewLease(@Param("id") String id, @Param("processing") FormalNoteOutboxEntity.Status processing,
                   @Param("owner") String owner, @Param("generation") long generation,
                   @Param("expiresAt") Instant expiresAt);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("""
            update FormalNoteOutboxEntity event
               set event.status = :done, event.leaseOwner = null,
                   event.leaseExpiresAt = null, event.processedAt = :processedAt,
                   event.lastError = null
             where event.id = :id and event.status = :processing
               and event.leaseOwner = :owner and event.generation = :generation
            """)
    int done(@Param("id") String id, @Param("processing") FormalNoteOutboxEntity.Status processing,
             @Param("done") FormalNoteOutboxEntity.Status done, @Param("owner") String owner,
             @Param("generation") long generation, @Param("processedAt") Instant processedAt);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("""
            update FormalNoteOutboxEntity event
               set event.status = :queued, event.availableAt = :availableAt,
                   event.leaseOwner = null, event.leaseExpiresAt = null,
                   event.lastError = :lastError
             where event.id = :id and event.status = :processing
               and event.leaseOwner = :owner and event.generation = :generation
            """)
    int retry(@Param("id") String id, @Param("processing") FormalNoteOutboxEntity.Status processing,
              @Param("queued") FormalNoteOutboxEntity.Status queued, @Param("owner") String owner,
              @Param("generation") long generation, @Param("availableAt") Instant availableAt,
              @Param("lastError") String lastError);
}
