ALTER TABLE user_sessions ADD COLUMN IF NOT EXISTS legacy_token varchar(255);
ALTER TABLE user_sessions ALTER COLUMN token_hash DROP NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uk_user_sessions_token_hash
    ON user_sessions (token_hash) WHERE token_hash IS NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uk_user_sessions_legacy_token
    ON user_sessions (legacy_token) WHERE legacy_token IS NOT NULL;
