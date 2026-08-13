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
@Table(name = "formal_note_outbox")
@Comment("正式笔记检索投影事件表")
class FormalNoteOutboxEntity {
    enum Status { QUEUED, PROCESSING, DONE }

    @Id
    @Column(length = 36)
    @Comment("正式笔记投影事件主键")
    private String id;

    @Column(name = "note_id", nullable = false, length = 36)
    @Comment("正式笔记主键")
    private String noteId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    @Comment("投影事件状态")
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

    protected FormalNoteOutboxEntity() {
    }

    FormalNoteOutboxEntity(String noteId) {
        this.id = java.util.UUID.randomUUID().toString();
        this.noteId = noteId;
        this.status = Status.QUEUED;
        this.availableAt = Instant.now();
        this.createdAt = Instant.now();
    }

    void claim(String owner, Instant expiresAt) {
        status = Status.PROCESSING;
        leaseOwner = owner;
        leaseExpiresAt = expiresAt;
        attemptCount++;
    }

    void done(Instant now) {
        status = Status.DONE;
        leaseOwner = null;
        leaseExpiresAt = null;
        processedAt = now;
        lastError = null;
    }

    void retry(Instant availableAt, String error) {
        status = Status.QUEUED;
        this.availableAt = availableAt;
        leaseOwner = null;
        leaseExpiresAt = null;
        lastError = error;
    }

    String noteId() { return noteId; }
    String id() { return id; }
    int attemptCount() { return attemptCount; }
}
