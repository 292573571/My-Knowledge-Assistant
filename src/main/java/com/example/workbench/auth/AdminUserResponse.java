package com.example.workbench.auth;

import java.time.Instant;

public record AdminUserResponse(
        String publicId,
        String account,
        String userName,
        SystemRole systemRole,
        Instant createdAt
) {
}
