package com.example.workbench.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import org.hibernate.annotations.Comment;

@Entity
@Table(name = "audit_events", indexes = {
        @Index(name = "idx_audit_workspace_created", columnList = "workspace_id,created_at"),
        @Index(name = "idx_audit_request", columnList = "request_id")
})
@Comment("业务审计事件表")
public class AuditEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Comment("审计事件主键")
    private Long id;

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
    @Comment("执行结果：成功、拒绝或失败")
    private AuditOutcome outcome;

    @Column(name = "reason_code", nullable = false, length = 80)
    @Comment("固定原因代码")
    private String reasonCode;

    @Column(name = "request_id", nullable = false, length = 64)
    @Comment("关联 HTTP 请求标识")
    private String requestId;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Comment("事件发生时间")
    private Instant createdAt;

    @Column(name = "previous_hash", nullable = false, updatable = false, length = 64)
    @Comment("前一条审计事件哈希")
    private String previousHash;

    @Column(name = "event_hash", nullable = false, updatable = false, unique = true, length = 64)
    @Comment("当前审计事件哈希")
    private String eventHash;

    protected AuditEvent() {
    }

    public AuditEvent(String actorPublicId, String workspaceId, AuditAction action, String resourceType,
                      String resourceId, AuditOutcome outcome, String reasonCode, String requestId) {
        this(actorPublicId, workspaceId, action, resourceType, resourceId, outcome, reasonCode, requestId, "GENESIS");
    }

    public AuditEvent(String actorPublicId, String workspaceId, AuditAction action, String resourceType,
                      String resourceId, AuditOutcome outcome, String reasonCode, String requestId,
                      String previousHash) {
        this.actorPublicId = safe(actorPublicId, "unknown", 64);
        this.workspaceId = safe(workspaceId, "unknown", 36);
        this.action = action;
        this.resourceType = safe(resourceType, "UNKNOWN", 32);
        this.resourceId = safe(resourceId, "unknown", 128);
        this.outcome = outcome;
        this.reasonCode = safe(reasonCode, "NONE", 80);
        this.requestId = safe(requestId, "unknown", 64);
        this.createdAt = Instant.now();
        this.previousHash = safe(previousHash, "GENESIS", 64);
        this.eventHash = hash(this.previousHash, this.actorPublicId, this.workspaceId, this.action,
                this.resourceType, this.resourceId, this.outcome, this.reasonCode, this.requestId, this.createdAt);
    }

    private String safe(String value, String fallback, int maxLength) {
        String normalized = value == null || value.isBlank() ? fallback : value;
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength);
    }

    private String hash(String previous, String actor, String workspace, AuditAction eventAction,
                        String type, String resource, AuditOutcome eventOutcome, String reason, String request,
                        Instant created) {
        try {
            String value = String.join("|", previous, actor, workspace, eventAction.name(), type, resource,
                    eventOutcome.name(), reason, request, created.toString());
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(64);
            for (byte item : digest) result.append(String.format("%02x", item));
            return result.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("审计日志哈希算法不可用", exception);
        }
    }

    public Long getId() { return id; }
    public String getActorPublicId() { return actorPublicId; }
    public String getWorkspaceId() { return workspaceId; }
    public AuditAction getAction() { return action; }
    public String getResourceType() { return resourceType; }
    public String getResourceId() { return resourceId; }
    public AuditOutcome getOutcome() { return outcome; }
    public String getReasonCode() { return reasonCode; }
    public String getRequestId() { return requestId; }
    public Instant getCreatedAt() { return createdAt; }
    public String getPreviousHash() { return previousHash; }
    public String getEventHash() { return eventHash; }
}
