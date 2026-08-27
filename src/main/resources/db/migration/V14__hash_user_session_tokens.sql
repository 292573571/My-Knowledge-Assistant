DO $$
BEGIN
    IF EXISTS (
        SELECT 1
          FROM information_schema.columns
         WHERE table_schema = current_schema()
           AND table_name = 'user_sessions'
           AND column_name = 'token'
    ) AND NOT EXISTS (
        SELECT 1
          FROM information_schema.columns
         WHERE table_schema = current_schema()
           AND table_name = 'user_sessions'
           AND column_name = 'legacy_token'
    ) THEN
        ALTER TABLE user_sessions RENAME COLUMN token TO legacy_token;
    END IF;
END $$;

ALTER TABLE user_sessions ADD COLUMN IF NOT EXISTS token_hash varchar(64);
CREATE UNIQUE INDEX IF NOT EXISTS uk_user_sessions_token_hash
    ON user_sessions (token_hash) WHERE token_hash IS NOT NULL;
