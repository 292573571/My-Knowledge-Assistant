-- 修复 V24 遗留的 workspace_id 语义缺陷。
--
-- V24 中写入了：
--     COALESCE('personal-' || owner_user_id, 'public-default')
-- 在 SQL 里 'personal-' || '' 的结果是 'personal-'（非空），因此 COALESCE 的兜底分支
-- 永远不会命中，owner_user_id 为空串的历史记录被写成损坏的 workspace_id = 'personal-'。
-- 与之对应的 Java 侧判定用的是 isBlank()（ownerUserId 为空时取 'public-default'），
-- 两侧语义不一致，于是这些行既不属于任何真实空间，也无法被检索命中，
-- 表现为「知识库列表显示已索引、检索却永远命中不到」的静默失败。
--
-- 实测样本（owner_user_id 为空串、workspace_id='personal-'、Chroma 中 0 chunk）：
--   docs/eval-case-api-report.md
--   docs/architecture/eval-run-storage.md
--   docs/architecture/normal-chat-data-flow.md
--   docs/architecture/rag-quality-loop.md

-- 1) 先把损坏的 workspace_id 补回 V24 本应写入的正确值（幂等）
UPDATE document_indexes
   SET workspace_id = 'public-default',
       visibility   = 'PUBLIC'
 WHERE btrim(coalesce(workspace_id, '')) = 'personal-'
   AND btrim(coalesce(owner_user_id, '')) = '';

-- 2) 删除孤儿索引行：这些文档源自早期批量扫描 docs/ 时入库的项目自带架构文档，
--    源文件已不在部署目录中，无法重新入库，保留只会让知识库文档数虚高。
--    条件限定到已知的具体 path，且要求 owner 仍为空，避免误伤后续正常入库的同名文档。
DELETE FROM document_indexes
 WHERE btrim(coalesce(owner_user_id, '')) = ''
   AND path IN (
       'docs/eval-case-api-report.md',
       'docs/architecture/eval-run-storage.md',
       'docs/architecture/normal-chat-data-flow.md',
       'docs/architecture/rag-quality-loop.md'
   );

-- 3) 加约束防止同类损坏 ID 再次写入（'personal-' 必须带用户 ID）
ALTER TABLE document_indexes DROP CONSTRAINT IF EXISTS ck_document_indexes_personal_workspace_has_owner;

ALTER TABLE document_indexes
    ADD CONSTRAINT ck_document_indexes_personal_workspace_has_owner
    CHECK (workspace_id IS NULL OR btrim(workspace_id) <> 'personal-');
