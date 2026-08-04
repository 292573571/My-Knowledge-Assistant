CREATE TABLE IF NOT EXISTS document_chunks (
    id varchar(196) PRIMARY KEY,
    document_id varchar(128) NOT NULL,
    chunk_index integer NOT NULL,
    content text NOT NULL,
    title text,
    source varchar(255) NOT NULL,
    path varchar(255) NOT NULL,
    file_name varchar(255) NOT NULL,
    file_type varchar(16) NOT NULL,
    content_hash varchar(128) NOT NULL,
    heading_path text,
    heading_level integer NOT NULL,
    start_offset integer NOT NULL,
    end_offset integer NOT NULL,
    chunk_type varchar(32) NOT NULL,
    category varchar(32) NOT NULL,
    owner_user_id varchar(64) NOT NULL,
    workspace_id varchar(36) NOT NULL,
    visibility varchar(16) NOT NULL,
    page_number integer NOT NULL,
    created_at timestamptz NOT NULL,
    document_version bigint NOT NULL,
    search_text text GENERATED ALWAYS AS (
        coalesce(file_name, '') || E'\n' || coalesce(title, '') || E'\n'
        || coalesce(heading_path, '') || E'\n' || content
    ) STORED,
    search_vector tsvector GENERATED ALWAYS AS (
        to_tsvector('simple', coalesce(file_name, '') || ' ' || coalesce(title, '') || ' '
            || coalesce(heading_path, '') || ' ' || content)
    ) STORED,
    CONSTRAINT uk_document_chunks_document_index UNIQUE (document_id, chunk_index)
);

CREATE INDEX IF NOT EXISTS idx_document_chunks_search_vector
    ON document_chunks USING GIN (search_vector);
CREATE INDEX IF NOT EXISTS idx_document_chunks_scope
    ON document_chunks (visibility, workspace_id, owner_user_id);
CREATE INDEX IF NOT EXISTS idx_document_chunks_document
    ON document_chunks (document_id, chunk_index);
