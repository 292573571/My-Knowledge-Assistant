-- 过期会话清理：按 expires_at 有序取批次，缺少索引时每次调度都要全表扫描 + 排序。
CREATE INDEX IF NOT EXISTS idx_user_sessions_expires_at
    ON user_sessions (expires_at);

INSERT INTO scheduled_jobs (job_key, enabled, next_run_at, interval_seconds, failure_count)
VALUES ('user-session-cleanup', true, CURRENT_TIMESTAMP, 3600, 0)
ON CONFLICT (job_key) DO NOTHING;
