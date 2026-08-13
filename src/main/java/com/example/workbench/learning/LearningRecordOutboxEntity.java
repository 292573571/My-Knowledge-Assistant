package com.example.workbench.learning;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.Comment;

@Entity
@Table(name = "learning_record_outbox")
@Comment("学习记录派生投影事件表")
class LearningRecordOutboxEntity {

    enum Status { QUEUED, PROCESSING, DONE }

    @Id
    @Column(length = 36)
    @Comment("投影事件主键")
    private String id;

    @Column(name = "record_id", nullable = false, length = 36)
    @Comment("触发事件的学习记录主键")
    private String recordId;

    @Column(name = "owner_user_id", nullable = false)
    @Comment("投影用户主键")
    private Long ownerUserId;

    @Column(name = "workspace_id", length = 36)
    @Comment("需要投影的知识空间")
    private String workspaceId;

    @Column(name = "record_date", nullable = false)
    @Comment("需要投影的学习日期")
    private java.time.LocalDate recordDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    @Comment("投影事件类型")
    private Status status;

    @Column(name = "available_at", nullable = false)
    @Comment("事件可处理时间")
    private Instant availableAt;

    @Column(name = "attempt_count", nullable = false)
    @Comment("投影重试次数")
    private int attemptCount;

    @Column(name = "lease_owner", length = 100)
    @Comment("事件租约持有实例")
    private String leaseOwner;

    @Column(name = "lease_expires_at")
    @Comment("事件租约过期时间")
    private Instant leaseExpiresAt;

    @Column(name = "last_error", length = 1000)
    @Comment("最近一次投影错误")
    private String lastError;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Comment("事件创建时间")
    private Instant createdAt;

    @Column(name = "processed_at")
    @Comment("事件完成时间")
    private Instant processedAt;

    protected LearningRecordOutboxEntity() {
    }

    LearningRecordOutboxEntity(String recordId, Long ownerUserId, String workspaceId, java.time.LocalDate recordDate) {
        this.id = java.util.UUID.randomUUID().toString();
        this.recordId = recordId;
        this.ownerUserId = ownerUserId;
        this.workspaceId = workspaceId;
        this.recordDate = recordDate;
        this.status = Status.QUEUED;
        this.availableAt = Instant.now();
        this.createdAt = Instant.now();
    }

    void claim(String owner, Instant expiresAt) {
        this.status = Status.PROCESSING;
        this.leaseOwner = owner;
        this.leaseExpiresAt = expiresAt;
        this.attemptCount++;
    }

    void done(Instant now) {
        this.status = Status.DONE;
        this.leaseOwner = null;
        this.leaseExpiresAt = null;
        this.processedAt = now;
        this.lastError = null;
    }

    void retry(Instant availableAt, String error) {
        this.status = Status.QUEUED;
        this.availableAt = availableAt;
        this.leaseOwner = null;
        this.leaseExpiresAt = null;
        this.lastError = error;
    }

    String id() { return id; }
    String recordId() { return recordId; }
    Long ownerUserId() { return ownerUserId; }
    String workspaceId() { return workspaceId; }
    java.time.LocalDate recordDate() { return recordDate; }
    Status status() { return status; }
    Instant availableAt() { return availableAt; }
    Instant leaseExpiresAt() { return leaseExpiresAt; }
    int attemptCount() { return attemptCount; }
}
