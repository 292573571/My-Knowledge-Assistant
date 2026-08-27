-- 移除明文会话令牌列，彻底消除 DB 泄露风险。
-- 1. 删除尚未迁移到 token_hash 的旧会话（这些会话的明文令牌不再被接受，用户需重新登录）
-- 2. 删除 legacy_token 唯一索引
-- 3. 删除 legacy_token 列
-- 4. 恢复 token_hash NOT NULL 约束

DELETE FROM user_sessions WHERE token_hash IS NULL;

DROP INDEX IF EXISTS uk_user_sessions_legacy_token;

ALTER TABLE user_sessions DROP COLUMN IF EXISTS legacy_token;

ALTER TABLE user_sessions ALTER COLUMN token_hash SET NOT NULL;
