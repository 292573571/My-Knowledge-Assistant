package com.example.workbench.rag;

import java.time.Instant;

/**
 * 长文档处理批次响应。
 */
public record DocumentTaskBatchResponse(
        int batchIndex,
        int startPage,
        int endPage,
        DocumentTaskBatchStatus status,
        int chunkCount,
        String errorMessage,
        int attemptCount,
        Instant finishedAt
) {
    static DocumentTaskBatchResponse from(DocumentTaskBatchEntity batch) {
        return new DocumentTaskBatchResponse(batch.getBatchIndex(), batch.getStartPage(), batch.getEndPage(),
                batch.getStatus(), batch.getChunkCount(), batch.getErrorMessage(), batch.getAttemptCount(),
                batch.getFinishedAt());
    }
}
