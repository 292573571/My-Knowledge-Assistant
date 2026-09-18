package com.example.workbench.agent;

public enum MaintenanceAction {
    RETRY_TASK,
    SYNC_WORKSPACE,
    REBUILD_INDEX,
    /** 超级管理员：为全部知识空间提交索引重建任务。 */
    REBUILD_ALL_INDEX,
    /** 超级管理员：把某个模型池模型设为默认对话模型。 */
    SET_DEFAULT_MODEL,
    /** 超级管理员：调整某个用户的系统角色，期望角色放在 payload。 */
    SET_USER_ROLE,
    /** 管理员：清理普通运行日志（不影响审计日志）。 */
    CLEAR_SYSTEM_LOGS,
    DELETE_DOCUMENT
}
