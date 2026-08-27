package com.example.workbench.audit;

import java.time.Instant;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

public interface AuditOutboxRepository extends JpaRepository<AuditOutboxEvent, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<AuditOutboxEvent> findFirstByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
            AuditOutboxEvent.Status status, Instant now);
}
