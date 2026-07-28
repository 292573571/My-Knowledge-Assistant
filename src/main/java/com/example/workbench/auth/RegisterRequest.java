package com.example.workbench.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @NotBlank(message = "账号不能为空")
        @Pattern(regexp = "[A-Za-z0-9_-]{3,64}", message = "账号只能使用 3-64 位字母、数字、下划线或连字符")
        String account,
        @NotBlank(message = "密码不能为空")
        @Size(min = 8, max = 128, message = "密码长度必须为 8-128 位")
        String password
) {
}
