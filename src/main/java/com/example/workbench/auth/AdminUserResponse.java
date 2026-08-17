package com.example.workbench.auth;

import java.time.Instant;

public record AdminUserResponse(
        String publicId,
        String account,
        String email,
        String phone,
        String userName,
        SystemRole systemRole,
        Instant createdAt
) {
}
