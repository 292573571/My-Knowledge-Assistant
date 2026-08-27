package com.example.workbench.rag;

import com.example.workbench.workspace.WorkspaceRole;
import com.example.workbench.workspace.WorkspaceType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.time.Instant;
import org.hibernate.annotations.Comment;

@Entity
@Table(name = "document_tasks", uniqueConstraints = @UniqueConstraint(
        name = "uk_document_task_request_scope",
        columnNames = {"actor_user_id", "workspace_id", "client_request_id"}))
@Comment("文档异步处理任务表")
public class DocumentTaskEntity {

    @Id
    @Column(name = "task_id", length = 36)
    @Comment("任务业务标识")
    private String taskId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    @Comment("任务类型")
    private DocumentTaskType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    @Comment("任务状态")
    private DocumentTaskStatus status;

    @Column(nullable = false, length = 32)
    @Comment("当前处理阶段")
    private String stage;

    @Column(nullable = false)
    @Comment("任务完成百分比")
    private int progress;

    @Column(name = "workspace_id", nullable = false, length = 36)
    @Comment("所属知识空间主键")
    private String workspaceId;

    @Column(name = "actor_user_id", nullable = false, length = 64)
    @Comment("任务创建者业务标识")
    private String actorUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "workspace_role", nullable = false, length = 16)
    @Comment("创建任务时的空间角色")
    private WorkspaceRole workspaceRole;

    @Enumerated(EnumType.STRING)
    @Column(name = "workspace_type", nullable = false, length = 16)
    @Comment("知识空间类型")
    private WorkspaceType workspaceType;

    @Column(name = "file_name", nullable = false)
    @Comment("原始文件名")
    private String fileName;

    @Column(name = "source_path", nullable = false)
    @Comment("已持久化源文件路径")
    private String sourcePath;

    @Column(name = "client_request_id", length = 64)
    @Comment("上传请求幂等标识")
    private String clientRequestId;

    @Column(name = "document_id", length = 128)
    @Comment("处理成功后的文档标识")
    private String documentId;

    @Column(name = "attempt_count", nullable = false)
    @Comment("已执行次数")
    private int attemptCount;

    @Column(name = "max_attempts", nullable = false)
    @Comment("最大自动执行次数")
    private int maxAttempts;

    @Column(name = "next_attempt_at")
    @Comment("下次自动重试时间")
    private Instant nextAttemptAt;

    @Column(name = "error_message", length = 1000)
    @Comment("最近一次失败原因")
    private String errorMessage;

    @Column(name = "retryable")
    @Comment("失败后是否允许原任务重试")
    private Boolean retryable;

    @Column(name = "total_items")
    @Comment("批量任务计划处理的文件数")
    private Integer totalItems;

    @Column(name = "completed_items")
    @Comment("批量任务已处理的文件数")
    private Integer completedItems;

    @Column(name = "succeeded_items")
    @Comment("批量任务处理成功的文件数")
    private Integer succeededItems;

    @Column(name = "failed_items")
    @Comment("批量任务处理失败的文件数")
    private Integer failedItems;

    @Column(name = "result_chunks")
    @Comment("批量任务最终生成的分块数")
    private Integer resultChunks;

    @Column(name = "current_batch")
    @Comment("当前处理批次序号")
    private Integer currentBatch;

    @Column(name = "total_batches")
    @Comment("文档总批次数")
    private Integer totalBatches;

    @Column(name = "current_start_page")
    @Comment("当前批次起始页")
    private Integer currentStartPage;

    @Column(name = "current_end_page")
    @Comment("当前批次结束页")
    private Integer currentEndPage;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Comment("任务创建时间")
    private Instant createdAt;

    @Column(name = "started_at")
    @Comment("最近一次开始时间")
    private Instant startedAt;

    @Column(name = "finished_at")
    @Comment("任务完成时间")
    private Instant finishedAt;

    @Column(name = "active_workspace_key", unique = true, length = 36)
    @Comment("运行中任务的空间互斥键，非运行状态为空")
    private String activeWorkspaceKey;

    @Column(name = "worker_id", length = 64)
    @Comment("当前执行实例标识")
    private String workerId;

    @Column(name = "lease_expires_at")
    @Comment("执行租约到期时间")
    private Instant leaseExpiresAt;

    @Version
    @Comment("乐观锁版本")
    private long version;

    @Column(nullable = false)
    @Comment("任务租约 fencing generation")
    private long generation;

    protected DocumentTaskEntity() {
    }

    DocumentTaskEntity(String taskId, String fileName, String sourcePath, String clientRequestId,
                       String actorUserId, String workspaceId, WorkspaceRole role, WorkspaceType type) {
        this.taskId = taskId;
        this.type = DocumentTaskType.UPLOAD;
        this.status = DocumentTaskStatus.QUEUED;
        this.stage = "QUEUED";
        this.progress = 5;
        this.workspaceId = workspaceId;
        this.actorUserId = actorUserId;
        this.workspaceRole = role;
        this.workspaceType = type;
        this.fileName = fileName;
        this.sourcePath = sourcePath;
        this.clientRequestId = clientRequestId;
        this.attemptCount = 0;
        this.maxAttempts = 3;
        this.nextAttemptAt = Instant.now();
        this.createdAt = Instant.now();
        this.generation = 0;
    }

    DocumentTaskEntity(String taskId, DocumentTaskType type, String label, String sourcePath,
                       String actorUserId, String workspaceId, WorkspaceRole role, WorkspaceType workspaceType) {
        this(taskId, label, sourcePath == null ? "" : sourcePath, null, actorUserId, workspaceId, role, workspaceType);
        this.type = type;
    }

    void start() {
        status = DocumentTaskStatus.RUNNING;
        stage = "PARSING";
        progress = 15;
        attemptCount++;
        startedAt = Instant.now();
        nextAttemptAt = null;
        errorMessage = null;
    }

    void updateProgress(String stage, int progress) {
        this.stage = stage;
        this.progress = Math.max(this.progress, Math.min(99, progress));
    }

    void updateBatchProgress(int totalItems, int completedItems, int succeededItems, int failedItems,
                             int resultChunks) {
        this.totalItems = Math.max(0, totalItems);
        this.completedItems = Math.max(0, completedItems);
        this.succeededItems = Math.max(0, succeededItems);
        this.failedItems = Math.max(0, failedItems);
        this.resultChunks = Math.max(0, resultChunks);
    }

    void updateBatchDetails(int currentBatch, int totalBatches, int startPage, int endPage) {
        this.currentBatch = Math.max(0, currentBatch);
        this.totalBatches = Math.max(0, totalBatches);
        this.currentStartPage = Math.max(0, startPage);
        this.currentEndPage = Math.max(0, endPage);
    }

    void succeed(String documentId) {
        this.documentId = documentId;
        status = DocumentTaskStatus.SUCCEEDED;
        stage = "DONE";
        progress = 100;
        finishedAt = Instant.now();
        errorMessage = null;
        retryable = null;
        releaseLease();
    }

    void fail(String message, boolean retryable) {
        errorMessage = message == null ? "文档处理失败" : message.substring(0, Math.min(message.length(), 1000));
        this.retryable = retryable;
        if (retryable && attemptCount < maxAttempts) {
            status = DocumentTaskStatus.RETRY_WAIT;
            stage = "RETRY_WAIT";
            nextAttemptAt = Instant.now().plusSeconds(30L * attemptCount);
        } else {
            status = DocumentTaskStatus.FAILED;
            stage = "FAILED";
            finishedAt = Instant.now();
        }
        releaseLease();
    }

    void retry() {
        status = DocumentTaskStatus.QUEUED;
        stage = "QUEUED";
        progress = 5;
        attemptCount = 0;
        nextAttemptAt = Instant.now();
        finishedAt = null;
        errorMessage = null;
        retryable = null;
        releaseLease();
    }

    void recoverInterruptedExecution() {
        generation++;
        status = DocumentTaskStatus.QUEUED;
        stage = "QUEUED";
        nextAttemptAt = Instant.now();
        errorMessage = "服务重启，任务已重新排队";
        releaseLease();
    }

    void renewLease(String workerId, Instant leaseExpiresAt) {
        if (this.workerId != null && this.workerId.equals(workerId)) {
            this.leaseExpiresAt = leaseExpiresAt;
        }
    }

    boolean renewLease(String workerId, long generation, Instant leaseExpiresAt) {
        if (!ownsLease(workerId, generation)) return false;
        this.leaseExpiresAt = leaseExpiresAt;
        return true;
    }

    boolean ownsLease(String workerId, long generation) {
        return this.workerId != null && this.workerId.equals(workerId) && this.generation == generation
                && this.status == DocumentTaskStatus.RUNNING;
    }

    boolean succeed(String documentId, String workerId, long generation) {
        if (!ownsLease(workerId, generation)) return false;
        succeed(documentId);
        return true;
    }

    boolean fail(String message, boolean retryable, String workerId, long generation) {
        if (!ownsLease(workerId, generation)) return false;
        fail(message, retryable);
        return true;
    }

    private void releaseLease() {
        activeWorkspaceKey = null;
        workerId = null;
        leaseExpiresAt = null;
    }

    String getTaskId() { return taskId; }
    DocumentTaskType getType() { return type; }
    DocumentTaskStatus getStatus() { return status; }
    String getStage() { return stage; }
    int getProgress() { return progress; }
    String getWorkspaceId() { return workspaceId; }
    String getActorUserId() { return actorUserId; }
    WorkspaceRole getWorkspaceRole() { return workspaceRole; }
    WorkspaceType getWorkspaceType() { return workspaceType; }
    String getFileName() { return fileName; }
    String getSourcePath() { return sourcePath; }
    String getDocumentId() { return documentId; }
    int getAttemptCount() { return attemptCount; }
    int getMaxAttempts() { return maxAttempts; }
    Instant getNextAttemptAt() { return nextAttemptAt; }
    String getErrorMessage() { return errorMessage; }
    boolean isRetryable() { return Boolean.TRUE.equals(retryable); }
    int getTotalItems() { return totalItems == null ? 0 : totalItems; }
    int getCompletedItems() { return completedItems == null ? 0 : completedItems; }
    int getSucceededItems() { return succeededItems == null ? 0 : succeededItems; }
    int getFailedItems() { return failedItems == null ? 0 : failedItems; }
    int getResultChunks() { return resultChunks == null ? 0 : resultChunks; }
    int getCurrentBatch() { return currentBatch == null ? 0 : currentBatch; }
    int getTotalBatches() { return totalBatches == null ? 0 : totalBatches; }
    int getCurrentStartPage() { return currentStartPage == null ? 0 : currentStartPage; }
    int getCurrentEndPage() { return currentEndPage == null ? 0 : currentEndPage; }
    Instant getCreatedAt() { return createdAt; }
    Instant getStartedAt() { return startedAt; }
    Instant getFinishedAt() { return finishedAt; }
    String getWorkerId() { return workerId; }
    Instant getLeaseExpiresAt() { return leaseExpiresAt; }
    long getGeneration() { return generation; }
}
