CREATE TABLE IF NOT EXISTS teaching_attempts (
    check_id varchar(36) PRIMARY KEY,
    owner_key varchar(80) NOT NULL,
    workspace_id varchar(36) NOT NULL,
    session_id varchar(64) NOT NULL,
    topic varchar(200) NOT NULL,
    question varchar(2000) NOT NULL,
    expires_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL,
    answer text,
    check_completed boolean NOT NULL DEFAULT false,
    response_json text,
    practice_id varchar(36) UNIQUE,
    practice_question varchar(2000),
    practice_answer text,
    practice_completed boolean NOT NULL DEFAULT false,
    practice_response_json text,
    version bigint NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_teaching_attempts_session
    ON teaching_attempts (owner_key, workspace_id, session_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_teaching_attempts_expiry
    ON teaching_attempts (expires_at);
