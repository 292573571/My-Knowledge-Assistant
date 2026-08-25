-- 为知识空间增加层级（组织→团队）所需的上级空间列
ALTER TABLE workspaces ADD COLUMN parent_workspace_id VARCHAR(36);

ALTER TABLE workspaces ADD CONSTRAINT fk_workspace_parent
    FOREIGN KEY (parent_workspace_id) REFERENCES workspaces (id);
