-- 修复 audit_events 上由早期 ddl-auto=update 自动生成的 CHECK 约束
-- 与 AuditAction / AuditOutcome Java 枚举不同步导致的插入失败。
-- 现象：audit-event-outbox-retry 定时任务每 5s 报
--   ERROR: new row for relation "audit_events" violates check constraint "audit_events_action_check"
--   (SQLState 23514)
-- 根因：约束是在枚举较小的时候生成的，后续枚举新增取值（如 MODEL_CONFIG_DEFAULT_CHANGE、
--        PERSONAL_MODEL_CONFIG_CHANGE、AUDIT_LOG_DELETE 等）未同步进约束，导致带新动作的审计事件
--        写入 audit_events 时触发约束冲突，补偿队列表 audit_event_outbox 中的事件永久卡在 QUEUED。
-- 修复：将约束重建为当前枚举全集。枚举历史上仅新增取值、从未重命名/删除，故现有数据必然满足新约束。
-- 同时重建 outcome 约束保持对称，避免同类漂移。

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
        'USER_ROLE_CHANGE',
        'MODEL_CONFIG_CREATE',
        'MODEL_CONFIG_UPDATE',
        'MODEL_CONFIG_DELETE',
        'MODEL_CONFIG_DEFAULT_CHANGE',
        'PERSONAL_MODEL_CONFIG_CHANGE',
        'AUDIT_LOG_DELETE'
    ));

ALTER TABLE audit_events DROP CONSTRAINT IF EXISTS audit_events_outcome_check;
ALTER TABLE audit_events ADD CONSTRAINT audit_events_outcome_check
    CHECK (outcome IN ('SUCCESS', 'DENIED', 'FAILED'));
