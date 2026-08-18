CREATE TABLE IF NOT EXISTS ai_models (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(64) NOT NULL,
    base_url VARCHAR(256) NOT NULL,
    api_key VARCHAR(256) NOT NULL,
    model VARCHAR(128) NOT NULL,
    temperature DOUBLE PRECISION,
    top_p DOUBLE PRECISION,
    max_output_tokens INTEGER,
    request_timeout_ms BIGINT,
    fallback_models VARCHAR(256),
    is_default BOOLEAN NOT NULL DEFAULT FALSE,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);

CREATE TABLE IF NOT EXISTS user_model_config (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL UNIQUE,
    mode VARCHAR(24) NOT NULL DEFAULT 'FOLLOW_DEFAULT',
    model_id BIGINT,
    name VARCHAR(64),
    base_url VARCHAR(256),
    api_key VARCHAR(256),
    model VARCHAR(128),
    temperature DOUBLE PRECISION,
    top_p DOUBLE PRECISION,
    max_output_tokens INTEGER,
    request_timeout_ms BIGINT,
    fallback_models VARCHAR(256),
    updated_at TIMESTAMP NOT NULL
);
