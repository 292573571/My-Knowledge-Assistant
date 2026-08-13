CREATE TABLE IF NOT EXISTS scheduled_jobs (
    job_key varchar(100) PRIMARY KEY,
    enabled boolean NOT NULL DEFAULT true,
    next_run_at timestamptz NOT NULL,
    interval_seconds bigint NOT NULL,
    lease_owner varchar(100),
    lease_expires_at timestamptz,
    last_started_at timestamptz,
    last_finished_at timestamptz,
    last_error varchar(1000),
    failure_count integer NOT NULL DEFAULT 0,
    version bigint NOT NULL DEFAULT 0
);

INSERT INTO scheduled_jobs (job_key, enabled, next_run_at, interval_seconds, failure_count)
VALUES
    ('teaching-attempt-cleanup', true, CURRENT_TIMESTAMP, 300, 0),
    ('maintenance-action-cleanup', true, CURRENT_TIMESTAMP, 300, 0)
ON CONFLICT (job_key) DO NOTHING;
