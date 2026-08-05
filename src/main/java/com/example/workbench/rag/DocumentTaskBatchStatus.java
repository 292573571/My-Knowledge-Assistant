package com.example.workbench.rag;

/**
 * 文档批次处理状态。
 */
public enum DocumentTaskBatchStatus {
    QUEUED,
    RUNNING,
    SUCCEEDED,
    FAILED
}
