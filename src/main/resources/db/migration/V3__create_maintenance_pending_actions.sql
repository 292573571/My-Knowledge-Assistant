CREATE TABLE IF NOT EXISTS maintenance_pending_actions (
    confirmation_token varchar(36) PRIMARY KEY,
    user_id varchar(64) NOT NULL,
    workspace_id varchar(36) NOT NULL,
    action varchar(32) NOT NULL,
    target_id varchar(128) NOT NULL,
    description varchar(1000) NOT NULL,
    expires_at timestamptz NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_maintenance_pending_actions_expiry
    ON maintenance_pending_actions (expires_at);
