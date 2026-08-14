CREATE TABLE IF NOT EXISTS learning_session_events (
    event_id varchar(64) PRIMARY KEY,
    session_id varchar(64) NOT NULL REFERENCES learning_sessions(session_id) ON DELETE CASCADE,
    user_id bigint NOT NULL,
    workspace_id varchar(36) NOT NULL,
    client_request_id varchar(100) NOT NULL,
    event_type varchar(32) NOT NULL,
    payload_json text NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT uk_learning_session_event_request UNIQUE (session_id, client_request_id)
);

CREATE INDEX IF NOT EXISTS idx_learning_session_events_scope
    ON learning_session_events (user_id, workspace_id, session_id, created_at);
