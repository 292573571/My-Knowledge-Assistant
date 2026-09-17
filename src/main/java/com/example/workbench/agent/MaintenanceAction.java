package com.example.workbench.agent;

public enum MaintenanceAction {
    RETRY_TASK,
    SYNC_WORKSPACE,
    REBUILD_INDEX,
    /** 超级管理员：为全部知识空间提交索引重建任务。 */
    REBUILD_ALL_INDEX,
    DELETE_DOCUMENT
}
