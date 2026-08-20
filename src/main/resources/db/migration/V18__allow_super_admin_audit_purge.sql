CREATE TABLE IF NOT EXISTS audit_purge_events (
    id              BIGSERIAL PRIMARY KEY,
    actor_public_id VARCHAR(64) NOT NULL,
    deleted_count   BIGINT NOT NULL,
    request_id      VARCHAR(64) NOT NULL,
    created_at      TIMESTAMP NOT NULL
);

CREATE OR REPLACE FUNCTION reject_audit_event_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF current_setting('app.audit_delete_allowed', true) = 'super_admin'
       AND TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    RAISE EXCEPTION 'audit_events is append-only';
END;
$$;

CREATE OR REPLACE FUNCTION reject_audit_purge_event_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'audit_purge_events is append-only';
END;
$$;

DROP TRIGGER IF EXISTS audit_purge_events_immutable ON audit_purge_events;
CREATE TRIGGER audit_purge_events_immutable
BEFORE UPDATE OR DELETE ON audit_purge_events
FOR EACH ROW EXECUTE FUNCTION reject_audit_purge_event_mutation();
