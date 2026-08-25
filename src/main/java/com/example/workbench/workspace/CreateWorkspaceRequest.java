package com.example.workbench.workspace;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateWorkspaceRequest(
        @NotBlank(message = "空间名称不能为空")
        @Size(max = 80, message = "空间名称不能超过 80 个字符")
        String name,
        String parentId
) {
}
