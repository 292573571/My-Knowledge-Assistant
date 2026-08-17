package com.example.workbench.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @NotBlank(message = "邮箱不能为空")
        @Email(message = "邮箱格式不正确")
        String email,
        @NotBlank(message = "验证码不能为空")
        String code,
        @NotBlank(message = "密码不能为空")
        @Size(min = 8, max = 128, message = "密码长度必须为 8-128 位")
        String password
) {
}
