CREATE TABLE IF NOT EXISTS learning_sessions (
    session_id varchar(64) PRIMARY KEY,
    user_id bigint NOT NULL,
    workspace_id varchar(36) NOT NULL,
    conversation_id varchar(64) NOT NULL,
    title varchar(120) NOT NULL,
    topic varchar(120),
    mode varchar(24) NOT NULL,
    stage varchar(24) NOT NULL,
    status varchar(24) NOT NULL,
    user_level varchar(24) NOT NULL,
    expires_at timestamptz,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    CONSTRAINT uk_learning_session_scope UNIQUE (user_id, workspace_id, session_id)
);

CREATE INDEX IF NOT EXISTS idx_learning_sessions_scope_updated
    ON learning_sessions (user_id, workspace_id, updated_at DESC);

CREATE INDEX IF NOT EXISTS idx_learning_sessions_conversation
    ON learning_sessions (conversation_id);
