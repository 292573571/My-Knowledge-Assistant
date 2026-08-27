CREATE TABLE IF NOT EXISTS audit_event_outbox (
    id                  VARCHAR(36) PRIMARY KEY,
    actor_public_id     VARCHAR(64) NOT NULL,
    workspace_id        VARCHAR(36) NOT NULL,
    action              VARCHAR(48) NOT NULL,
    resource_type       VARCHAR(32) NOT NULL,
    resource_id         VARCHAR(128) NOT NULL,
    outcome             VARCHAR(16) NOT NULL,
    reason_code         VARCHAR(80) NOT NULL,
    request_id          VARCHAR(64) NOT NULL,
    status              VARCHAR(16) NOT NULL,
    attempt_count       INTEGER NOT NULL DEFAULT 0,
    next_attempt_at     TIMESTAMPTZ NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_audit_event_outbox_due
    ON audit_event_outbox (status, next_attempt_at, created_at);

INSERT INTO scheduled_jobs (job_key, enabled, next_run_at, interval_seconds, failure_count)
VALUES ('audit-event-outbox-retry', true, CURRENT_TIMESTAMP, 5, 0)
ON CONFLICT (job_key) DO NOTHING;
