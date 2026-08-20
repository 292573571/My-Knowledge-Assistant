CREATE TABLE IF NOT EXISTS audit_events (
    id              BIGSERIAL PRIMARY KEY,
    actor_public_id VARCHAR(64) NOT NULL,
    workspace_id    VARCHAR(36) NOT NULL,
    action          VARCHAR(48) NOT NULL,
    resource_type   VARCHAR(32) NOT NULL,
    resource_id     VARCHAR(128) NOT NULL,
    outcome         VARCHAR(16) NOT NULL,
    reason_code     VARCHAR(80) NOT NULL,
    request_id      VARCHAR(64) NOT NULL,
    created_at      TIMESTAMP NOT NULL,
    previous_hash   VARCHAR(64) NOT NULL DEFAULT 'GENESIS',
    event_hash      VARCHAR(64) NOT NULL DEFAULT 'LEGACY'
);

ALTER TABLE audit_events ADD COLUMN IF NOT EXISTS previous_hash VARCHAR(64);
ALTER TABLE audit_events ADD COLUMN IF NOT EXISTS event_hash VARCHAR(64);
UPDATE audit_events SET previous_hash = 'GENESIS' WHERE previous_hash IS NULL;
UPDATE audit_events SET event_hash = LPAD(TO_HEX(id), 64, '0') WHERE event_hash IS NULL;
ALTER TABLE audit_events ALTER COLUMN previous_hash SET NOT NULL;
ALTER TABLE audit_events ALTER COLUMN event_hash SET NOT NULL;

CREATE INDEX IF NOT EXISTS idx_audit_workspace_created ON audit_events (workspace_id, created_at);
CREATE INDEX IF NOT EXISTS idx_audit_request ON audit_events (request_id);
CREATE UNIQUE INDEX IF NOT EXISTS uq_audit_event_hash ON audit_events (event_hash);

CREATE OR REPLACE FUNCTION reject_audit_event_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'audit_events is append-only';
END;
$$;

DROP TRIGGER IF EXISTS audit_events_immutable ON audit_events;
CREATE TRIGGER audit_events_immutable
BEFORE UPDATE OR DELETE ON audit_events
FOR EACH ROW EXECUTE FUNCTION reject_audit_event_mutation();
