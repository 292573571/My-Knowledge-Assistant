package com.example.workbench.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.Comment;

@Entity
@Table(name = "audit_event_outbox")
@Comment("审计事件失败补偿队列表")
public class AuditOutboxEvent {
    enum Status { QUEUED, PROCESSING, DONE }

    @Id
    @Column(length = 36)
    @Comment("补偿事件主键")
    private String id;

    @Column(name = "actor_public_id", nullable = false, length = 64)
    @Comment("操作用户公开标识")
    private String actorPublicId;

    @Column(name = "workspace_id", nullable = false, length = 36)
    @Comment("关联知识空间主键")
    private String workspaceId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 48)
    @Comment("审计动作")
    private AuditAction action;

    @Column(name = "resource_type", nullable = false, length = 32)
    @Comment("资源类型")
    private String resourceType;

    @Column(name = "resource_id", nullable = false, length = 128)
    @Comment("资源标识")
    private String resourceId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    @Comment("执行结果")
    private AuditOutcome outcome;

    @Column(name = "reason_code", nullable = false, length = 80)
    @Comment("固定原因代码")
    private String reasonCode;

    @Column(name = "request_id", nullable = false, length = 64)
    @Comment("关联请求标识")
    private String requestId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    @Comment("补偿事件状态")
    private Status status;

    @Column(name = "attempt_count", nullable = false)
    @Comment("补偿尝试次数")
    private int attemptCount;

    @Column(name = "next_attempt_at", nullable = false)
    @Comment("下次补偿时间")
    private Instant nextAttemptAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Comment("补偿事件创建时间")
    private Instant createdAt;

    protected AuditOutboxEvent() {
    }

    public AuditOutboxEvent(String actorPublicId, String workspaceId, AuditAction action, String resourceType,
                            String resourceId, AuditOutcome outcome, String reasonCode, String requestId) {
        this.id = java.util.UUID.randomUUID().toString();
        this.actorPublicId = safe(actorPublicId, "unknown", 64);
        this.workspaceId = safe(workspaceId, "unknown", 36);
        this.action = action;
        this.resourceType = safe(resourceType, "UNKNOWN", 32);
        this.resourceId = safe(resourceId, "unknown", 128);
        this.outcome = outcome;
        this.reasonCode = safe(reasonCode, "NONE", 80);
        this.requestId = safe(requestId, "unknown", 64);
        this.status = Status.QUEUED;
        this.nextAttemptAt = Instant.now();
        this.createdAt = Instant.now();
    }

    void start() { status = Status.PROCESSING; attemptCount++; }
    void done() { status = Status.DONE; }
    void retry(Instant nextAttemptAt) { status = Status.QUEUED; this.nextAttemptAt = nextAttemptAt; }
    String actorPublicId() { return actorPublicId; }
    String workspaceId() { return workspaceId; }
    AuditAction action() { return action; }
    String resourceType() { return resourceType; }
    String resourceId() { return resourceId; }
    AuditOutcome outcome() { return outcome; }
    String reasonCode() { return reasonCode; }
    String requestId() { return requestId; }
    int attemptCount() { return attemptCount; }

    private String safe(String value, String fallback, int maxLength) {
        String normalized = value == null || value.isBlank() ? fallback : value;
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength);
    }
}
