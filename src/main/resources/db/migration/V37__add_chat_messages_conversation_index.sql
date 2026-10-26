-- 会话历史查询：findByConversationIdOrderByCreatedAtDesc 需要按 conversation_id 过滤、
-- created_at 倒序取最近 N 条。此前该表只有主键与 uk_chat_messages_request_role
-- （partial index，client_request_id IS NOT NULL），无法支撑该访问路径，
-- 随消息量增长会退化为全表扫描 + 排序。
CREATE INDEX IF NOT EXISTS idx_chat_messages_conversation_created
    ON chat_messages (conversation_id, created_at DESC);
