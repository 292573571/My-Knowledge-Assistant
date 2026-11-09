-- 中文分词稀疏检索：search_tokens 由应用侧 jieba 分词写入，
-- search_vector 改为对分词结果建 simple 配置索引，使 FTS 分支对中文真正生效。
ALTER TABLE document_chunks ADD COLUMN IF NOT EXISTS search_tokens text;

-- 回填：迁移后、应用重建索引前，先用原文兜底，行为与旧版一致不退化。
UPDATE document_chunks SET search_tokens = coalesce(search_text, '') WHERE search_tokens IS NULL;

DROP INDEX IF EXISTS idx_document_chunks_search_vector;
ALTER TABLE document_chunks DROP COLUMN search_vector;
ALTER TABLE document_chunks ADD COLUMN search_vector tsvector GENERATED ALWAYS AS (
    to_tsvector('simple', coalesce(search_tokens, ''))
) STORED;
CREATE INDEX idx_document_chunks_search_vector
    ON document_chunks USING GIN (search_vector);
