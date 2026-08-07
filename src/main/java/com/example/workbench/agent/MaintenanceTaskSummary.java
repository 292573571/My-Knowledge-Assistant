package com.example.workbench.agent;

import com.example.workbench.rag.DocumentTaskResponse;

/**
 * 面向 Agent 的精简任务信息。
 */
public record MaintenanceTaskSummary(
        String taskId,
        String type,
        String fileName,
        String status,
        String stage,
        int progress,
        String errorMessage,
        boolean retryable,
        int currentBatch,
        int totalBatches
) {
    static MaintenanceTaskSummary from(DocumentTaskResponse task) {
        return new MaintenanceTaskSummary(task.taskId(), task.type().name(), task.fileName(), task.status().name(),
                task.stage(), task.progress(), task.errorMessage(), task.retryable(), task.currentBatch(),
                task.totalBatches());
    }
}
