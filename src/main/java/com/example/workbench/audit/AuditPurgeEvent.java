package com.example.workbench.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.Comment;

@Entity
@Table(name = "audit_purge_events")
@Comment("审计日志删除留痕表")
public class AuditPurgeEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Comment("删除留痕主键")
    private Long id;

    @Column(name = "actor_public_id", nullable = false, updatable = false, length = 64)
    @Comment("超级管理员公开标识")
    private String actorPublicId;

    @Column(name = "deleted_count", nullable = false, updatable = false)
    @Comment("删除审计记录数量")
    private long deletedCount;

    @Column(name = "request_id", nullable = false, updatable = false, length = 64)
    @Comment("删除请求标识")
    private String requestId;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Comment("删除发生时间")
    private Instant createdAt;

    protected AuditPurgeEvent() {
    }

    public AuditPurgeEvent(String actorPublicId, long deletedCount, String requestId) {
        this.actorPublicId = safe(actorPublicId, "unknown", 64);
        this.deletedCount = Math.max(0, deletedCount);
        this.requestId = safe(requestId, "unknown", 64);
        this.createdAt = Instant.now();
    }

    private String safe(String value, String fallback, int maxLength) {
        String normalized = value == null || value.isBlank() ? fallback : value;
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength);
    }

    public Long getId() { return id; }
    public String getActorPublicId() { return actorPublicId; }
    public long getDeletedCount() { return deletedCount; }
    public String getRequestId() { return requestId; }
    public Instant getCreatedAt() { return createdAt; }
}
