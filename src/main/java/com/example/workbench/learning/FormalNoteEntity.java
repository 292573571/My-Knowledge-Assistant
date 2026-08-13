package com.example.workbench.learning;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import org.hibernate.annotations.Comment;

@Entity
@Table(name = "formal_notes")
@Comment("正式学习笔记事实表")
class FormalNoteEntity {

    @Id
    @Column(length = 36)
    @Comment("正式笔记主键")
    private String id;

    @Column(name = "owner_user_id", nullable = false)
    @Comment("笔记所有者用户主键")
    private Long ownerUserId;

    @Column(name = "workspace_id", length = 36)
    @Comment("笔记所属知识空间")
    private String workspaceId;

    @Column(name = "note_date", nullable = false)
    @Comment("正式笔记日期")
    private LocalDate noteDate;

    @Column(name = "file_name", nullable = false, length = 255)
    @Comment("兼容导出的文件名")
    private String fileName;

    @Column(nullable = false, length = 512)
    @Comment("兼容导出的文件路径")
    private String path;

    @Column(nullable = false, columnDefinition = "text")
    @Comment("正式笔记正文")
    private String content;

    @Column(name = "content_hash", nullable = false, length = 128)
    @Comment("正式笔记内容哈希")
    private String contentHash;

    @Column(name = "index_status", nullable = false, length = 32)
    @Comment("正式笔记检索索引状态")
    private String indexStatus;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Comment("正式笔记创建时间")
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    @Comment("正式笔记更新时间")
    private Instant updatedAt;

    protected FormalNoteEntity() {
    }

    FormalNoteEntity(Long ownerUserId, String workspaceId, LocalDate noteDate, String fileName, String path,
                     String content, String contentHash) {
        this.id = java.util.UUID.randomUUID().toString();
        this.ownerUserId = ownerUserId;
        this.workspaceId = workspaceId;
        this.noteDate = noteDate;
        this.fileName = fileName;
        this.path = path;
        this.content = content;
        this.contentHash = contentHash;
        this.indexStatus = "PENDING";
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    void update(String fileName, String path, String content, String contentHash) {
        this.fileName = fileName;
        this.path = path;
        this.content = content;
        this.contentHash = contentHash;
        this.indexStatus = "PENDING";
        this.updatedAt = Instant.now();
    }

    void markIndexed() {
        this.indexStatus = "INDEXED";
        this.updatedAt = Instant.now();
    }

    void markIndexFailed() {
        this.indexStatus = "FAILED";
        this.updatedAt = Instant.now();
    }

    String id() { return id; }
    Long ownerUserId() { return ownerUserId; }
    String workspaceId() { return workspaceId; }
    LocalDate noteDate() { return noteDate; }
    String fileName() { return fileName; }
    String path() { return path; }
    String content() { return content; }
}
