package com.example.workbench.workspace;

import java.time.Instant;

public record WorkspaceMemberResponse(
        String publicId,
        String userName,
        WorkspaceRole role,
        Instant joinedAt
) {
}
