package com.example.workbench.auth;

public record AuthResponse(
        String token,
        String account,
        String email,
        String phone,
        String userName,
        String publicId,
        String avatarUrl,
        java.time.Instant createdAt,
        SystemRole systemRole
) {
}
