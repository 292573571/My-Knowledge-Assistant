package com.example.workbench.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LoginRequest(
        @NotBlank(message = "账号不能为空")
        @Size(max = 128, message = "账号长度不能超过 128 个字符")
        String account,
        @NotBlank(message = "密码不能为空")
        String password
) {
}
