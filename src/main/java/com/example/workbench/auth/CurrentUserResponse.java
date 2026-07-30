package com.example.workbench.auth;

public record CurrentUserResponse(
        String account,
        String userName,
        String publicId,
        String avatarUrl,
        java.time.Instant createdAt,
        SystemRole systemRole
) {
}
