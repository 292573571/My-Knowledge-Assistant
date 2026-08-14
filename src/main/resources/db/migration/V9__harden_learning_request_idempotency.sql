ALTER TABLE learning_session_events
    ADD COLUMN IF NOT EXISTS request_hash varchar(64),
    ADD COLUMN IF NOT EXISTS status varchar(16),
    ADD COLUMN IF NOT EXISTS processing_expires_at timestamptz;

UPDATE learning_session_events
SET request_hash = COALESCE(request_hash, repeat('0', 64)),
    status = COALESCE(status, 'SUCCEEDED');

ALTER TABLE learning_session_events
    ALTER COLUMN request_hash SET NOT NULL,
    ALTER COLUMN status SET NOT NULL,
    ALTER COLUMN payload_json DROP NOT NULL;

ALTER TABLE learning_session_events
    DROP CONSTRAINT IF EXISTS uk_learning_session_event_request;

ALTER TABLE learning_session_events
    ADD CONSTRAINT uk_learning_session_event_request
        UNIQUE (session_id, event_type, client_request_id);

CREATE UNIQUE INDEX IF NOT EXISTS uk_learning_session_single_processing
    ON learning_session_events (session_id)
    WHERE status = 'PROCESSING';
