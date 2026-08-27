package com.example.workbench.audit;

import com.example.workbench.auth.AppUser;
import jakarta.persistence.EntityManager;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class AuditService {
    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private final AuditEventRepository repository;
    private final AuditPurgeEventRepository purgeEventRepository;
    private final EntityManager entityManager;
    private final AuditOutboxService outboxService;
    private final AuditEventWriter eventWriter;

    public AuditService(AuditEventRepository repository, AuditPurgeEventRepository purgeEventRepository,
                        EntityManager entityManager) {
        this(repository, purgeEventRepository, entityManager, null, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public AuditService(AuditEventRepository repository, AuditPurgeEventRepository purgeEventRepository,
                        EntityManager entityManager, AuditOutboxService outboxService, AuditEventWriter eventWriter) {
        this.repository = repository;
        this.purgeEventRepository = purgeEventRepository;
        this.entityManager = entityManager;
        this.outboxService = outboxService;
        this.eventWriter = eventWriter;
    }

    public void record(AppUser actor, String workspaceId, AuditAction action, String resourceType,
                       String resourceId, AuditOutcome outcome, String reasonCode, String requestId) {
        record(actor == null ? "unknown" : actor.getPublicId(), workspaceId, action, resourceType, resourceId,
                outcome, reasonCode, requestId);
    }

    public synchronized void record(String actorPublicId, String workspaceId, AuditAction action, String resourceType,
                                     String resourceId, AuditOutcome outcome, String reasonCode, String requestId) {
        try {
            if (eventWriter == null) {
                AuditEvent previous = repository.findTopByOrderByIdDesc();
                repository.save(new AuditEvent(actorPublicId, workspaceId, action, resourceType, resourceId,
                        outcome, reasonCode, requestId, previous == null ? "GENESIS" : previous.getEventHash()));
            } else {
                eventWriter.write(actorPublicId, workspaceId, action, resourceType, resourceId,
                        outcome, reasonCode, requestId);
            }
        } catch (RuntimeException exception) {
            if (outboxService != null) {
                try {
                    outboxService.enqueue(actorPublicId, workspaceId, action, resourceType, resourceId,
                            outcome, reasonCode, requestId);
                } catch (RuntimeException enqueueException) {
                    log.error("审计写入和补偿入队均失败 action={} resourceType={} reasonType={}", action,
                            resourceType, enqueueException.getClass().getSimpleName());
                }
            } else {
                log.error("审计写入失败 action={} resourceType={} reasonType={}", action, resourceType,
                        exception.getClass().getSimpleName());
            }
        }
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
