package com.example.workbench.audit;

import java.time.Instant;

public record AuditEventResponse(
        Long id,
        String actorPublicId,
        String workspaceId,
        AuditAction action,
        String resourceType,
        String resourceId,
        AuditOutcome outcome,
        String reasonCode,
        String requestId,
        Instant createdAt
) {
}
