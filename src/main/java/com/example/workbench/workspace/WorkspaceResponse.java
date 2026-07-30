package com.example.workbench.workspace;

import java.time.Instant;

public record WorkspaceResponse(
        String id,
        String name,
        WorkspaceType type,
        WorkspaceRole role,
        Instant createdAt
) {
}
