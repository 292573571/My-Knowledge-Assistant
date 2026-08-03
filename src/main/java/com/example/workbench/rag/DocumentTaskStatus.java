package com.example.workbench.rag;

/**
 * 文档异步任务状态。
 */
public enum DocumentTaskStatus {
    QUEUED,
    RUNNING,
    RETRY_WAIT,
    SUCCEEDED,
    FAILED
}
