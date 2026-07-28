package com.example.workbench.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record LoginRequest(
        @NotBlank(message = "账号不能为空")
        @Pattern(regexp = "[A-Za-z0-9_-]{3,64}", message = "账号格式不正确")
        String account,
        @NotBlank(message = "密码不能为空")
        String password
) {
}
