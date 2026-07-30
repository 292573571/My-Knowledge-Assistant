package com.example.workbench.workspace;

public record WorkspaceAccessContext(
        String userId,
        String workspaceId,
        WorkspaceRole role,
        WorkspaceType type
) {
    public WorkspaceAccessContext(String userId, String workspaceId, WorkspaceRole role) {
        this(userId, workspaceId, role, WorkspaceType.TEAM);
    }

    public boolean canWrite() {
        return role == WorkspaceRole.OWNER || role == WorkspaceRole.EDITOR;
    }
}
