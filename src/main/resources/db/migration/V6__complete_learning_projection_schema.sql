-- V5 已可能在环境中执行过；后续字段和调度任务必须通过新版本迁移补齐。
ALTER TABLE learning_record_outbox
    ADD COLUMN IF NOT EXISTS workspace_id varchar(36);

INSERT INTO scheduled_jobs (job_key, enabled, next_run_at, interval_seconds, failure_count)
VALUES ('formal-note-projection', true, CURRENT_TIMESTAMP, 5, 0)
ON CONFLICT (job_key) DO NOTHING;
