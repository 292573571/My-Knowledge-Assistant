package com.example.workbench.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpdateProfileRequest(
        @NotBlank(message = "用户名称不能为空")
        @Size(max = 64, message = "用户名称不能超过 64 个字符")
        String userName
) {
}
