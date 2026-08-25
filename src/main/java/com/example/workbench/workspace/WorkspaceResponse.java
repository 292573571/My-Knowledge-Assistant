package com.example.workbench.workspace;

import java.time.Instant;

public record WorkspaceResponse(
        String id,
        String name,
        WorkspaceType type,
        WorkspaceRole role,
        Instant createdAt,
        String parentId
) {
    public WorkspaceResponse(String id, String name, WorkspaceType type, WorkspaceRole role, Instant createdAt) {
        this(id, name, type, role, createdAt, null);
    }
}
