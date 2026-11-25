-- V44: 向量存储从 Chroma 迁移到 pgvector。
-- bge-m3 输出 1024 维；embedding 与切片正文同表，保存与索引更新可同事务原子生效。

CREATE EXTENSION IF NOT EXISTS vector;

ALTER TABLE document_chunks ADD COLUMN IF NOT EXISTS embedding vector(1024);

-- 余弦距离检索索引（HNSW）；切片量级为千级，构建成本低。
CREATE INDEX IF NOT EXISTS idx_document_chunks_embedding
    ON document_chunks USING hnsw (embedding vector_cosine_ops);
