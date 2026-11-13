-- 知识沉淀闭环：消息反馈、评测题来源、wiki 笔记来源溯源
-- 1) chat_feedback：用户对 AI 回答的 👍/👎 反馈（阶段2 使用）
CREATE TABLE IF NOT EXISTS chat_feedback (
    id              BIGSERIAL PRIMARY KEY,
    conversation_id VARCHAR(64)  NOT NULL,
    message_id      BIGINT       NOT NULL,
    user_id         BIGINT       NOT NULL REFERENCES app_users(id),
    workspace_id    VARCHAR(36)  NOT NULL,
    rating          SMALLINT     NOT NULL CHECK (rating IN (1, -1)),
    reason          VARCHAR(500),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uk_chat_feedback_message_user UNIQUE (message_id, user_id)
);
CREATE INDEX IF NOT EXISTS idx_chat_feedback_workspace_created
    ON chat_feedback (workspace_id, created_at DESC);

-- 2) eval_cases：记录由哪条聊天消息反馈沉淀而来（阶段2 使用）
ALTER TABLE eval_cases ADD COLUMN IF NOT EXISTS source_message_id BIGINT;

-- 3) wiki_pages：笔记来源溯源（阶段4 使用）
ALTER TABLE wiki_pages ADD COLUMN IF NOT EXISTS source_type VARCHAR(16);
ALTER TABLE wiki_pages ADD COLUMN IF NOT EXISTS source_ref VARCHAR(128);
