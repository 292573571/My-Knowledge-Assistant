package com.example.workbench.workspace;

import jakarta.validation.constraints.NotNull;

public record UpdateWorkspaceMemberRoleRequest(
        @NotNull(message = "成员角色不能为空") WorkspaceRole role
) {
}
