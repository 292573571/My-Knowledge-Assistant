-- 同步 audit_events 的 action CHECK 约束与 AuditAction 枚举。
-- 本次新增 DOCUMENT_REBUILD / SYSTEM_LOG_CLEAR / SYSTEM_AGENT_ACTION 三个取值，
-- 若不更新约束，带这些动作的审计事件写入会违反 audit_events_action_check（SQLState 23514），
-- 并使补偿队列 audit_event_outbox 的事件卡在 QUEUED。枚举仅新增取值，故现有数据必然满足新约束。

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
