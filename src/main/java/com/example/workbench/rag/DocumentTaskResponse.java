package com.example.workbench.rag;

import java.time.Instant;

/**
 * 文档异步任务响应。
 */
public record DocumentTaskResponse(
        String taskId,
        DocumentTaskType type,
        DocumentTaskStatus status,
        String stage,
        int progress,
        String workspaceId,
        String fileName,
        String documentId,
        int attemptCount,
        int maxAttempts,
        String errorMessage,
        Instant createdAt,
        Instant startedAt,
        Instant finishedAt,
        boolean documentDeleted
) {
    static DocumentTaskResponse from(DocumentTaskEntity task) {
        return from(task, false);
    }

    static DocumentTaskResponse from(DocumentTaskEntity task, boolean documentDeleted) {
        return new DocumentTaskResponse(task.getTaskId(), task.getType(), task.getStatus(), task.getStage(),
                task.getProgress(), task.getWorkspaceId(), task.getFileName(), task.getDocumentId(),
                task.getAttemptCount(), task.getMaxAttempts(), task.getErrorMessage(), task.getCreatedAt(),
                task.getStartedAt(), task.getFinishedAt(), documentDeleted);
    }
}
