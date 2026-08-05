package com.example.workbench.rag;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.Comment;

/**
 * 记录长文档任务的页面批次，支持查看失败批次和后续定向恢复。
 */
@Entity
@Table(name = "document_task_batches")
@Comment("长文档异步任务批次表")
public class DocumentTaskBatchEntity {

    @Id
    @Column(name = "batch_id", length = 80)
    @Comment("批次业务标识")
    private String batchId;

    @Column(name = "task_id", nullable = false, length = 36)
    @Comment("所属任务标识")
    private String taskId;

    @Column(name = "batch_index", nullable = false)
    @Comment("批次序号")
    private int batchIndex;

    @Column(name = "start_page", nullable = false)
    @Comment("起始页码")
    private int startPage;

    @Column(name = "end_page", nullable = false)
    @Comment("结束页码")
    private int endPage;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    @Comment("批次状态")
    private DocumentTaskBatchStatus status;

    @Column(name = "chunk_count", nullable = false)
    @Comment("批次生成分块数")
    private int chunkCount;

    @Column(name = "error_message", length = 1000)
    @Comment("批次失败原因")
    private String errorMessage;

    @Column(name = "attempt_count", nullable = false)
    @Comment("批次执行次数")
    private int attemptCount;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Comment("批次创建时间")
    private Instant createdAt;

    @Column(name = "finished_at")
    @Comment("批次完成时间")
    private Instant finishedAt;

    protected DocumentTaskBatchEntity() {
    }

    public DocumentTaskBatchEntity(String taskId, int batchIndex, int startPage, int endPage) {
        this.batchId = taskId + "-batch-" + batchIndex;
        this.taskId = taskId;
        this.batchIndex = batchIndex;
        this.startPage = startPage;
        this.endPage = endPage;
        this.status = DocumentTaskBatchStatus.QUEUED;
        this.createdAt = Instant.now();
    }

    public void start(int startPage, int endPage) {
        this.startPage = Math.max(0, startPage);
        this.endPage = Math.max(this.startPage, endPage);
        status = DocumentTaskBatchStatus.RUNNING;
        attemptCount++;
        errorMessage = null;
        finishedAt = null;
    }

    public void succeed(int chunks) {
        status = DocumentTaskBatchStatus.SUCCEEDED;
        chunkCount = Math.max(0, chunks);
        finishedAt = Instant.now();
    }

    public void fail(String message) {
        status = DocumentTaskBatchStatus.FAILED;
        errorMessage = message == null ? "批次处理失败" : message.substring(0, Math.min(1000, message.length()));
        finishedAt = Instant.now();
    }

    public String getBatchId() { return batchId; }
    public String getTaskId() { return taskId; }
    public int getBatchIndex() { return batchIndex; }
    public int getStartPage() { return startPage; }
    public int getEndPage() { return endPage; }
    public DocumentTaskBatchStatus getStatus() { return status; }
    public int getChunkCount() { return chunkCount; }
    public String getErrorMessage() { return errorMessage; }
    public int getAttemptCount() { return attemptCount; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getFinishedAt() { return finishedAt; }
}
