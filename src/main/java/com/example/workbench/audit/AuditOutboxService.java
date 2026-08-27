package com.example.workbench.audit;

import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuditOutboxService {
    private final AuditOutboxRepository repository;
    private final TransactionTemplate transactions;
    private final AuditEventRepository auditRepository;
    private final jakarta.persistence.EntityManager entityManager;

    public AuditOutboxService(AuditOutboxRepository repository,
                              org.springframework.transaction.PlatformTransactionManager transactionManager,
                              AuditEventRepository auditRepository,
                              jakarta.persistence.EntityManager entityManager) {
        this.repository = repository;
        this.transactions = new TransactionTemplate(transactionManager);
        this.auditRepository = auditRepository;
        this.entityManager = entityManager;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void enqueue(String actorPublicId, String workspaceId, AuditAction action, String resourceType,
                        String resourceId, AuditOutcome outcome, String reasonCode, String requestId) {
        transactions.executeWithoutResult(status -> repository.save(
                new AuditOutboxEvent(actorPublicId, workspaceId, action, resourceType, resourceId,
                        outcome, reasonCode, requestId)));
    }

    public void projectOne() {
        try {
            transactions.executeWithoutResult(status -> {
                AuditOutboxEvent event = repository
                        .findFirstByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
                                AuditOutboxEvent.Status.QUEUED, Instant.now())
                        .orElse(null);
                if (event == null) return;
                event.start();
                var lockQuery = entityManager.createNativeQuery("SELECT pg_advisory_xact_lock(hashtext('audit_events'))");
                if (lockQuery != null) lockQuery.getSingleResult();
                AuditEvent previous = auditRepository.findTopByOrderByIdDesc();
                auditRepository.save(new AuditEvent(event.actorPublicId(), event.workspaceId(), event.action(),
                        event.resourceType(), event.resourceId(), event.outcome(), event.reasonCode(),
                        event.requestId(), previous == null ? "GENESIS" : previous.getEventHash()));
                event.done();
                repository.save(event);
            });
        } catch (RuntimeException exception) {
            throw exception;
        }
    }
}
