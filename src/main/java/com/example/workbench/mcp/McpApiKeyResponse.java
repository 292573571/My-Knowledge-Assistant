package com.example.workbench.mcp;

import java.time.Instant;

/** MCP 凭证视图：绝不返回哈希或明文，仅用于列表展示。 */
public record McpApiKeyResponse(
        Long id,
        String name,
        String keyPrefix,
        boolean enabled,
        Instant expiresAt,
        Instant lastUsedAt,
        Instant revokedAt,
        Instant createdAt
) {
    public static McpApiKeyResponse from(McpApiKeyEntity entity) {
        return new McpApiKeyResponse(
                entity.getId(),
                entity.getName(),
                entity.getKeyPrefix(),
                entity.isEnabled(),
                entity.getExpiresAt(),
                entity.getLastUsedAt(),
                entity.getRevokedAt(),
                entity.getCreatedAt());
    }
}
