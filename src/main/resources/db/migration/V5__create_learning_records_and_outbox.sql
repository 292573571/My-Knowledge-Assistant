CREATE TABLE IF NOT EXISTS learning_records (
    id varchar(36) PRIMARY KEY,
    owner_user_id bigint NOT NULL,
    workspace_id varchar(36),
    record_date date NOT NULL,
    record_type varchar(32) NOT NULL,
    question text,
    answer text,
    topic varchar(200),
    session_id varchar(128),
    conversation_id varchar(128),
    message_id bigint,
    attempt_id varchar(36),
    practice_id varchar(36),
    score integer,
    max_score integer,
    passed boolean,
    feedback text,
    weak_point text,
    review_explanation text,
    review_suggestion text,
    sources_json text NOT NULL DEFAULT '[]',
    markdown text NOT NULL,
    source_key varchar(512) NOT NULL UNIQUE,
    legacy boolean NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    version bigint NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_learning_records_scope_date
    ON learning_records (owner_user_id, workspace_id, record_date, created_at);
CREATE INDEX IF NOT EXISTS idx_learning_records_topic
    ON learning_records (owner_user_id, workspace_id, topic, record_date);
CREATE UNIQUE INDEX IF NOT EXISTS uk_learning_records_attempt
    ON learning_records (attempt_id) WHERE attempt_id IS NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uk_learning_records_practice
    ON learning_records (practice_id) WHERE practice_id IS NOT NULL;

CREATE TABLE IF NOT EXISTS learning_record_outbox (
    id varchar(36) PRIMARY KEY,
    record_id varchar(36) NOT NULL,
    owner_user_id bigint NOT NULL,
    record_date date NOT NULL,
    status varchar(16) NOT NULL,
    available_at timestamptz NOT NULL,
    attempt_count integer NOT NULL DEFAULT 0,
    lease_owner varchar(100),
    lease_expires_at timestamptz,
    last_error varchar(1000),
    created_at timestamptz NOT NULL,
    processed_at timestamptz
);

CREATE INDEX IF NOT EXISTS idx_learning_record_outbox_due
    ON learning_record_outbox (status, available_at, lease_expires_at, created_at);

CREATE TABLE IF NOT EXISTS formal_notes (
    id varchar(36) PRIMARY KEY,
    owner_user_id bigint NOT NULL,
    workspace_id varchar(36),
    note_date date NOT NULL,
    file_name varchar(255) NOT NULL,
    path varchar(512) NOT NULL,
    content text NOT NULL,
    content_hash varchar(128) NOT NULL,
    index_status varchar(32) NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT uk_formal_notes_scope_date UNIQUE (owner_user_id, workspace_id, note_date)
);

CREATE INDEX IF NOT EXISTS idx_formal_notes_scope
    ON formal_notes (owner_user_id, workspace_id, note_date);

CREATE TABLE IF NOT EXISTS formal_note_outbox (
    id varchar(36) PRIMARY KEY,
    note_id varchar(36) NOT NULL,
    status varchar(16) NOT NULL,
    available_at timestamptz NOT NULL,
    attempt_count integer NOT NULL DEFAULT 0,
    lease_owner varchar(100),
    lease_expires_at timestamptz,
    last_error varchar(1000),
    created_at timestamptz NOT NULL,
    processed_at timestamptz
);

CREATE INDEX IF NOT EXISTS idx_formal_note_outbox_due
    ON formal_note_outbox (status, available_at, lease_expires_at, created_at);

INSERT INTO scheduled_jobs (job_key, enabled, next_run_at, interval_seconds, failure_count)
VALUES ('learning-record-projection', true, CURRENT_TIMESTAMP, 5, 0)
ON CONFLICT (job_key) DO NOTHING;
