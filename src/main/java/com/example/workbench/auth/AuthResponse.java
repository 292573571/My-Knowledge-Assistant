package com.example.workbench.auth;

public record AuthResponse(
        String token,
        String account,
        String userName,
        String publicId,
        String avatarUrl,
        java.time.Instant createdAt
) {
}
