package com.example.workbench.learning;

import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

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
}
