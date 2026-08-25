-- 工作空间新增「组织（根）」类型 ORG，原 CHECK 约束仅允许 PERSONAL/TEAM/PUBLIC，
-- 导致 WorkspaceHierarchyInitializer 创建 sunline 根组织时触发约束冲突、应用启动失败。
-- 此处删除旧约束并重建为包含 ORG 的四值约束（幂等，约束缺失时跳过删除）。
ALTER TABLE workspaces DROP CONSTRAINT IF EXISTS workspaces_type_check;

ALTER TABLE workspaces ADD CONSTRAINT workspaces_type_check
    CHECK (type IN ('PERSONAL', 'TEAM', 'PUBLIC', 'ORG'));
