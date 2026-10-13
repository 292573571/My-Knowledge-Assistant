-- P1: wiki 模式知识库的页面表
-- wiki 页以「可编辑页面」为最小单位，带层级（parent_id）与唯一 slug，
-- 内容以 Markdown 存储；保存时落盘为 docs/wiki/<workspaceId>/<pageId>.md 并由
-- DocumentIngestionService 切片向量化纳入 RAG 检索（类目 WIKI_PAGE）。
CREATE TABLE wiki_pages (
    id           VARCHAR(36)  NOT NULL,
    workspace_id VARCHAR(36)  NOT NULL,
    parent_id    VARCHAR(36),
    title        VARCHAR(255) NOT NULL,
    slug         VARCHAR(255) NOT NULL,
    content_md   TEXT,
    owner_id     VARCHAR(64)  NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT pk_wiki_pages PRIMARY KEY (id),
    CONSTRAINT uk_wiki_pages_workspace_slug UNIQUE (workspace_id, slug),
    CONSTRAINT fk_wiki_pages_parent FOREIGN KEY (parent_id) REFERENCES wiki_pages (id) ON DELETE SET NULL
);

CREATE INDEX idx_wiki_pages_workspace ON wiki_pages (workspace_id);
CREATE INDEX idx_wiki_pages_parent ON wiki_pages (parent_id);
