ALTER TABLE user_sessions RENAME COLUMN token TO token_hash;
DELETE FROM user_sessions;
