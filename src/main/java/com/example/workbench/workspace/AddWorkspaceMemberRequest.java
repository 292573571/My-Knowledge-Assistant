package com.example.workbench.workspace;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record AddWorkspaceMemberRequest(
        @NotBlank(message = "成员账号不能为空") String account,
        @NotNull(message = "成员角色不能为空") WorkspaceRole role
) {
}
