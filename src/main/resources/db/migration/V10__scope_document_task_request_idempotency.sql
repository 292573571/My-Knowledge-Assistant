DO $$
DECLARE
    constraint_name text;
    index_name text;
    composite_constraint_exists boolean;
BEGIN
    IF to_regclass('document_tasks') IS NOT NULL THEN
        FOR constraint_name IN
            SELECT c.conname
              FROM pg_constraint c
              JOIN pg_class table_ref ON table_ref.oid = c.conrelid
              JOIN pg_namespace schema_ref ON schema_ref.oid = table_ref.relnamespace
             WHERE schema_ref.nspname = current_schema()
               AND table_ref.relname = 'document_tasks'
               AND c.contype = 'u'
               AND c.conkey = ARRAY[
                   (SELECT attnum FROM pg_attribute
                     WHERE attrelid = table_ref.oid
                       AND attname = 'client_request_id'
                       AND NOT attisdropped)
               ]::smallint[]
        LOOP
            EXECUTE format('ALTER TABLE document_tasks DROP CONSTRAINT %I', constraint_name);
        END LOOP;

        FOR index_name IN
            SELECT index_ref.relname
              FROM pg_index index_info
              JOIN pg_class index_ref ON index_ref.oid = index_info.indexrelid
              JOIN pg_class table_ref ON table_ref.oid = index_info.indrelid
              JOIN pg_namespace schema_ref ON schema_ref.oid = table_ref.relnamespace
             WHERE schema_ref.nspname = current_schema()
               AND table_ref.relname = 'document_tasks'
               AND index_info.indisunique
               AND index_info.indnatts = 1
               AND index_info.indkey = ARRAY[
                   (SELECT attnum FROM pg_attribute
                     WHERE attrelid = table_ref.oid
                       AND attname = 'client_request_id'
                       AND NOT attisdropped)
               ]::int2vector
        LOOP
            EXECUTE format('DROP INDEX IF EXISTS %I', index_name);
        END LOOP;

        SELECT EXISTS (
            SELECT 1
              FROM pg_constraint c
              JOIN pg_class table_ref ON table_ref.oid = c.conrelid
              JOIN pg_namespace schema_ref ON schema_ref.oid = table_ref.relnamespace
             WHERE schema_ref.nspname = current_schema()
               AND table_ref.relname = 'document_tasks'
               AND c.conname = 'uk_document_task_request_scope'
        ) INTO composite_constraint_exists;

        IF NOT composite_constraint_exists THEN
            EXECUTE 'ALTER TABLE document_tasks
                     ADD CONSTRAINT uk_document_task_request_scope
                     UNIQUE (actor_user_id, workspace_id, client_request_id)';
        END IF;
    END IF;
END $$;
