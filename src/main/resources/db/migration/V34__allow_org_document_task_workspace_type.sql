ALTER TABLE document_tasks DROP CONSTRAINT IF EXISTS document_tasks_workspace_type_check;

ALTER TABLE document_tasks ADD CONSTRAINT document_tasks_workspace_type_check
    CHECK (workspace_type IN ('PERSONAL', 'TEAM', 'PUBLIC', 'ORG'));
