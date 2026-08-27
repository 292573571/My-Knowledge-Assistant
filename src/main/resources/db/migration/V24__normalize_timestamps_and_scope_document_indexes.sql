ALTER TABLE ai_models
    ALTER COLUMN created_at TYPE TIMESTAMPTZ USING created_at AT TIME ZONE 'Asia/Shanghai',
    ALTER COLUMN updated_at TYPE TIMESTAMPTZ USING updated_at AT TIME ZONE 'Asia/Shanghai';

ALTER TABLE user_model_config
    ALTER COLUMN updated_at TYPE TIMESTAMPTZ USING updated_at AT TIME ZONE 'Asia/Shanghai';

ALTER TABLE system_log
    ALTER COLUMN timestamp TYPE TIMESTAMPTZ USING timestamp AT TIME ZONE 'Asia/Shanghai';

ALTER TABLE audit_events
    ALTER COLUMN created_at TYPE TIMESTAMPTZ USING created_at AT TIME ZONE 'Asia/Shanghai';

ALTER TABLE audit_purge_events
    ALTER COLUMN created_at TYPE TIMESTAMPTZ USING created_at AT TIME ZONE 'Asia/Shanghai';

ALTER TABLE eval_imports
    ALTER COLUMN created_at TYPE TIMESTAMPTZ USING created_at AT TIME ZONE 'Asia/Shanghai';

ALTER TABLE eval_runs
    ALTER COLUMN created_at TYPE TIMESTAMPTZ USING created_at AT TIME ZONE 'Asia/Shanghai';

CREATE TABLE IF NOT EXISTS document_indexes (
    id BIGSERIAL PRIMARY KEY,
    document_id VARCHAR(128) NOT NULL,
    file_name VARCHAR(255) NOT NULL,
    path VARCHAR(255) NOT NULL,
    content_hash VARCHAR(128) NOT NULL,
    chunk_count INTEGER NOT NULL,
    ingested_at TIMESTAMPTZ NOT NULL,
    category VARCHAR(32) NOT NULL,
    index_status VARCHAR(32) NOT NULL,
    owner_user_id VARCHAR(64) NOT NULL,
    workspace_id VARCHAR(36),
    visibility VARCHAR(16)
);

DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM pg_attribute
        WHERE attrelid = 'document_indexes'::regclass
          AND attname = 'ingested_at'
          AND NOT attisdropped
          AND atttypid = 'int8'::regtype
    ) THEN
            ALTER TABLE document_indexes
                ALTER COLUMN ingested_at TYPE TIMESTAMPTZ
                USING to_timestamp(ingested_at / 1000.0);
    ELSIF EXISTS (
        SELECT 1 FROM pg_attribute
        WHERE attrelid = 'document_indexes'::regclass
          AND attname = 'ingested_at'
          AND NOT attisdropped
          AND format_type(atttypid, atttypmod) = 'timestamp without time zone'
    ) THEN
        ALTER TABLE document_indexes
            ALTER COLUMN ingested_at TYPE TIMESTAMPTZ
            USING ingested_at AT TIME ZONE 'Asia/Shanghai';
    END IF;
END $$;

DO $$
DECLARE
    constraint_name TEXT;
BEGIN
    UPDATE document_indexes
       SET workspace_id = CASE
           WHEN workspace_id IS NOT NULL AND btrim(workspace_id) <> '' THEN workspace_id
           ELSE COALESCE('personal-' || owner_user_id, 'public-default')
       END
     WHERE workspace_id IS NULL OR btrim(workspace_id) = '';

    FOR constraint_name IN
        SELECT c.conname
          FROM pg_constraint c
          JOIN pg_class t ON t.oid = c.conrelid
         WHERE t.oid = 'document_indexes'::regclass
           AND c.contype = 'u'
    LOOP
        EXECUTE format('ALTER TABLE document_indexes DROP CONSTRAINT %I', constraint_name);
    END LOOP;

    FOR constraint_name IN
        SELECT indexrelid::regclass::text
          FROM pg_index
         WHERE indrelid = 'document_indexes'::regclass
           AND indisunique
           AND NOT indisprimary
    LOOP
        EXECUTE format('DROP INDEX IF EXISTS %s', constraint_name);
    END LOOP;

    DELETE FROM document_indexes older
     WHERE EXISTS (
         SELECT 1
           FROM document_indexes newer
          WHERE older.id <> newer.id
            AND older.workspace_id = newer.workspace_id
            AND (older.document_id = newer.document_id
                 OR older.path = newer.path
                 OR older.content_hash = newer.content_hash)
            AND (newer.ingested_at > older.ingested_at
                 OR (newer.ingested_at = older.ingested_at AND newer.id > older.id))
     );

    ALTER TABLE document_indexes ALTER COLUMN workspace_id SET NOT NULL;
    ALTER TABLE document_indexes ADD CONSTRAINT uk_document_indexes_workspace_document
        UNIQUE (workspace_id, document_id);
    ALTER TABLE document_indexes ADD CONSTRAINT uk_document_indexes_workspace_path
        UNIQUE (workspace_id, path);
    ALTER TABLE document_indexes ADD CONSTRAINT uk_document_indexes_workspace_hash
        UNIQUE (workspace_id, content_hash);
END $$;
