-- P3: 知识图谱层（轻量图谱，默认 Postgres，零新中间件）
-- 节点 = wiki 页(WIKI_PAGE) 与用户用 [[概念名]] 显式链接出来的概念(TERM)；
-- 边 = 层级(PARENT_CHILD)、页间链接(WIKI_LINK)、页→概念提及(MENTIONS)。
-- 抽取完全确定性，由 WikiPageService 在保存/删除时驱动，幂等重建。
CREATE TABLE kg_entities (
    id            VARCHAR(36)  NOT NULL,
    workspace_id  VARCHAR(36)  NOT NULL,
    entity_type   VARCHAR(32)  NOT NULL,
    name          VARCHAR(512) NOT NULL,
    source_kind   VARCHAR(32),
    source_id     VARCHAR(512),
    mention_count INTEGER      NOT NULL DEFAULT 1,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT pk_kg_entities PRIMARY KEY (id),
    CONSTRAINT uk_kg_entities_ws_type_name UNIQUE (workspace_id, entity_type, name)
);

CREATE INDEX idx_kg_entities_workspace ON kg_entities (workspace_id);

CREATE TABLE kg_relations (
    id                 VARCHAR(36)  NOT NULL,
    workspace_id       VARCHAR(36)  NOT NULL,
    source_entity_id   VARCHAR(36)  NOT NULL,
    target_entity_id   VARCHAR(36)  NOT NULL,
    relation_type      VARCHAR(32)  NOT NULL,
    weight             INTEGER      NOT NULL DEFAULT 1,
    evidence_source_id VARCHAR(512),
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT pk_kg_relations PRIMARY KEY (id),
    CONSTRAINT fk_kg_relations_source FOREIGN KEY (source_entity_id) REFERENCES kg_entities (id) ON DELETE CASCADE,
    CONSTRAINT fk_kg_relations_target FOREIGN KEY (target_entity_id) REFERENCES kg_entities (id) ON DELETE CASCADE,
    CONSTRAINT uk_kg_relations_uniq UNIQUE (workspace_id, source_entity_id, target_entity_id, relation_type)
);

CREATE INDEX idx_kg_relations_workspace ON kg_relations (workspace_id);
CREATE INDEX idx_kg_relations_source ON kg_relations (source_entity_id);
CREATE INDEX idx_kg_relations_target ON kg_relations (target_entity_id);
CREATE INDEX idx_kg_relations_evidence ON kg_relations (workspace_id, evidence_source_id);
