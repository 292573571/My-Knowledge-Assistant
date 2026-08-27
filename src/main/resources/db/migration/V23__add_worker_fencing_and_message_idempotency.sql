ALTER TABLE IF EXISTS document_tasks
    ADD COLUMN IF NOT EXISTS generation bigint NOT NULL DEFAULT 0;

ALTER TABLE IF EXISTS learning_record_outbox
    ADD COLUMN IF NOT EXISTS generation bigint NOT NULL DEFAULT 0;

ALTER TABLE IF EXISTS formal_note_outbox
    ADD COLUMN IF NOT EXISTS generation bigint NOT NULL DEFAULT 0;

ALTER TABLE IF EXISTS learning_session_events
    ADD COLUMN IF NOT EXISTS generation bigint NOT NULL DEFAULT 0;

ALTER TABLE IF EXISTS chat_messages
    ADD COLUMN IF NOT EXISTS client_request_id varchar(100);

CREATE UNIQUE INDEX IF NOT EXISTS uk_chat_messages_request_role
    ON chat_messages (conversation_id, client_request_id, role)
    WHERE client_request_id IS NOT NULL;
