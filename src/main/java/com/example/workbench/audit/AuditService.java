package com.example.workbench.audit;

import com.example.workbench.auth.AppUser;
import jakarta.persistence.EntityManager;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AuditService {

    private final AuditEventRepository repository;
    private final AuditPurgeEventRepository purgeEventRepository;
    private final EntityManager entityManager;

    public AuditService(AuditEventRepository repository, AuditPurgeEventRepository purgeEventRepository,
                        EntityManager entityManager) {
        this.repository = repository;
        this.purgeEventRepository = purgeEventRepository;
        this.entityManager = entityManager;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(AppUser actor, String workspaceId, AuditAction action, String resourceType,
                       String resourceId, AuditOutcome outcome, String reasonCode, String requestId) {
        record(actor == null ? "unknown" : actor.getPublicId(), workspaceId, action, resourceType, resourceId,
                outcome, reasonCode, requestId);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public synchronized void record(String actorPublicId, String workspaceId, AuditAction action, String resourceType,
                                    String resourceId, AuditOutcome outcome, String reasonCode, String requestId) {
        AuditEvent previous = repository.findTopByOrderByIdDesc();
        repository.save(new AuditEvent(actorPublicId, workspaceId, action, resourceType, resourceId,
                outcome, reasonCode, requestId, previous == null ? "GENESIS" : previous.getEventHash()));
    }

    @Transactional(readOnly = true)
    public List<AuditEventResponse> list(String workspaceId) {
        return repository.findTop200ByWorkspaceIdOrderByCreatedAtDesc(workspaceId).stream()
                .map(event -> new AuditEventResponse(event.getId(), event.getActorPublicId(), event.getWorkspaceId(),
                        event.getAction(), event.getResourceType(), event.getResourceId(), event.getOutcome(),
                        event.getReasonCode(), event.getRequestId(), event.getCreatedAt(), event.getPreviousHash(),
                        event.getEventHash()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<AuditEventResponse> listAll() {
        return repository.findTop500ByOrderByCreatedAtDesc().stream().map(this::response).toList();
    }

    private AuditEventResponse response(AuditEvent event) {
        return new AuditEventResponse(event.getId(), event.getActorPublicId(), event.getWorkspaceId(), event.getAction(),
                event.getResourceType(), event.getResourceId(), event.getOutcome(), event.getReasonCode(),
                event.getRequestId(), event.getCreatedAt(), event.getPreviousHash(), event.getEventHash());
    }

    public void recordAnonymous(String actorPublicId, AuditAction action, String resourceType, String resourceId,
                                AuditOutcome outcome, String reasonCode, String requestId) {
        record(actorPublicId, "system", action, resourceType, resourceId, outcome, reasonCode, requestId);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public long purgeAll(AppUser actor, String requestId) {
        long count = repository.count();
        entityManager.createNativeQuery("SELECT set_config('app.audit_delete_allowed', 'super_admin', true)")
                .getSingleResult();
        entityManager.createNativeQuery("DELETE FROM audit_events").executeUpdate();
        purgeEventRepository.save(new AuditPurgeEvent(actor == null ? "unknown" : actor.getPublicId(), count, requestId));
        return count;
    }

    public AuditOutcome outcome(RuntimeException exception) {
        if (exception instanceof ResponseStatusException status && status.getStatusCode().is4xxClientError()) {
            return AuditOutcome.DENIED;
        }
        if (exception instanceof IllegalArgumentException) {
            return AuditOutcome.DENIED;
        }
        return AuditOutcome.FAILED;
    }

    public String reasonCode(RuntimeException exception) {
        if (exception instanceof ResponseStatusException status) {
            return "HTTP_" + status.getStatusCode().value();
        }
        return exception.getClass().getSimpleName();
    }
}
