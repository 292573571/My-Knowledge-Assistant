package com.example.workbench.audit;

import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuditEventWriter {
    private final AuditEventRepository repository;
    private final EntityManager entityManager;

    public AuditEventWriter(AuditEventRepository repository, EntityManager entityManager) {
        this.repository = repository;
        this.entityManager = entityManager;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void write(String actorPublicId, String workspaceId, AuditAction action, String resourceType,
                      String resourceId, AuditOutcome outcome, String reasonCode, String requestId) {
        entityManager.createNativeQuery("SELECT pg_advisory_xact_lock(hashtext('audit_events'))").getSingleResult();
        AuditEvent previous = repository.findTopByOrderByIdDesc();
        repository.save(new AuditEvent(actorPublicId, workspaceId, action, resourceType, resourceId,
                outcome, reasonCode, requestId, previous == null ? "GENESIS" : previous.getEventHash()));
    }
}
