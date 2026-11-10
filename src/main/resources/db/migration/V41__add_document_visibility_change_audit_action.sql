-- audit_events 的 action CHECK 约束补上 DOCUMENT_VISIBILITY_CHANGE。
-- RagController 文档可见性变更会写该动作的审计事件（AuditAction.DOCUMENT_VISIBILITY_CHANGE），
-- 但 V33 的白名单漏了它：写入触发 SQLState 23514，audit_event_outbox 中该事件卡在 QUEUED
-- 每 5 秒重试一次，永不成功且刷屏 ERROR 日志（2026-11-10 线上实测，outbox 积压 7 条）。
-- 枚举仅新增取值，现有数据必然满足新约束。

ALTER TABLE audit_events DROP CONSTRAINT IF EXISTS audit_events_action_check;
ALTER TABLE audit_events ADD CONSTRAINT audit_events_action_check
    CHECK (action IN (
        'LOGIN_SUCCESS',
        'LOGIN_FAILURE',
        'LOGOUT',
        'PASSWORD_CHANGE',
        'WORKSPACE_CREATE',
        'WORKSPACE_MEMBER_ADD',
        'WORKSPACE_MEMBER_ROLE_CHANGE',
        'WORKSPACE_MEMBER_REMOVE',
        'DOCUMENT_UPLOAD',
        'DOCUMENT_DELETE',
        'DOCUMENT_REBUILD',
        'DOCUMENT_VISIBILITY_CHANGE',
        'USER_ROLE_CHANGE',
        'MODEL_CONFIG_CREATE',
        'MODEL_CONFIG_UPDATE',
        'MODEL_CONFIG_DELETE',
        'MODEL_CONFIG_DEFAULT_CHANGE',
        'PERSONAL_MODEL_CONFIG_CHANGE',
        'SYSTEM_LOG_CLEAR',
        'SYSTEM_AGENT_ACTION',
        'AUDIT_LOG_DELETE'
    ));
