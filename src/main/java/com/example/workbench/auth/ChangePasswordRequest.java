package com.example.workbench.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ChangePasswordRequest(
        @NotBlank(message = "当前密码不能为空")
        String currentPassword,
        @NotBlank(message = "新密码不能为空")
        @Size(min = 8, max = 128, message = "新密码长度必须为 8-128 位")
        String newPassword,
        @NotBlank(message = "请再次输入新密码")
        String confirmPassword
) {
}
