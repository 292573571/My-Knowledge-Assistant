package com.example.workbench.audit;

import com.example.workbench.auth.AppUser;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AuditService {

    private final AuditEventRepository repository;

    public AuditService(AuditEventRepository repository) {
        this.repository = repository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(AppUser actor, String workspaceId, AuditAction action, String resourceType,
                       String resourceId, AuditOutcome outcome, String reasonCode, String requestId) {
        repository.save(new AuditEvent(actor.getPublicId(), workspaceId, action, resourceType, resourceId,
                outcome, reasonCode, requestId));
    }

    @Transactional(readOnly = true)
    public List<AuditEventResponse> list(String workspaceId) {
        return repository.findTop200ByWorkspaceIdOrderByCreatedAtDesc(workspaceId).stream()
                .map(event -> new AuditEventResponse(event.getId(), event.getActorPublicId(), event.getWorkspaceId(),
                        event.getAction(), event.getResourceType(), event.getResourceId(), event.getOutcome(),
                        event.getReasonCode(), event.getRequestId(), event.getCreatedAt()))
                .toList();
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
