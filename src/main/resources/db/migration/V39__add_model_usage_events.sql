-- 模型调用用量事件表：为用量仪表盘提供 token 消耗与调用统计
CREATE TABLE model_usage_events (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT,
    conversation_id VARCHAR(128),
    model VARCHAR(160) NOT NULL,
    mode VARCHAR(16) NOT NULL,
    prompt_tokens INT NOT NULL DEFAULT 0,
    completion_tokens INT NOT NULL DEFAULT 0,
    total_tokens INT NOT NULL DEFAULT 0,
    duration_ms BIGINT NOT NULL DEFAULT 0,
    success BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_model_usage_user_created ON model_usage_events (user_id, created_at);
CREATE INDEX idx_model_usage_created ON model_usage_events (created_at);
