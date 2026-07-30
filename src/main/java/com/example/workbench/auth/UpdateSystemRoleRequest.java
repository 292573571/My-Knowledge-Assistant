package com.example.workbench.auth;

import jakarta.validation.constraints.NotNull;

public record UpdateSystemRoleRequest(
        @NotNull(message = "系统角色不能为空") SystemRole systemRole
) {
}
