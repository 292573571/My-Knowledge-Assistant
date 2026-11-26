-- V44: 向量存储从 Chroma 迁移到 pgvector。
-- bge-m3 输出 1024 维；embedding 与切片正文同表，保存与索引更新可同事务原子生效。
-- 注意：vector 类型装在 public schema（CREATE EXTENSION 默认），而业务表在
-- my_knowledge_assistant schema —— 必须用 public.vector 限定，否则 Flyway 会话
-- （search_path 仅业务 schema）报 type "vector" does not exist。

CREATE EXTENSION IF NOT EXISTS vector;

ALTER TABLE document_chunks ADD COLUMN IF NOT EXISTS embedding public.vector(1024);

-- 余弦距离检索索引（HNSW）；切片量级为千级，构建成本低。
CREATE INDEX IF NOT EXISTS idx_document_chunks_embedding
    ON document_chunks USING hnsw (embedding public.vector_cosine_ops);
