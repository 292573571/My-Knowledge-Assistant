-- 修正 chat_feedback.rating 列类型：V42 建为 SMALLINT，但实体 Integer 在
-- ddl-auto=validate 下要求 INTEGER（首次发版 642d779 因此启动失败回滚）。
ALTER TABLE chat_feedback ALTER COLUMN rating TYPE INTEGER;
