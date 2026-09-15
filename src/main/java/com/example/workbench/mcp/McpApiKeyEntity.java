package com.example.workbench.mcp;

import com.example.workbench.auth.AppUser;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.Comment;

@Entity
@Table(name = "mcp_api_keys")
@Comment("MCP 客户端长期访问凭证表")
public class McpApiKeyEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Comment("凭证主键")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    @Comment("所属用户主键")
    private AppUser user;

    @Column(name = "name", nullable = false, length = 100)
    @Comment("凭证名称(便于用户辨识用途)")
    private String name;

    @Column(name = "key_prefix", nullable = false, length = 24)
    @Comment("明文凭证前缀(仅用于展示)")
    private String keyPrefix;

    @Column(name = "key_hash", nullable = false, unique = true, length = 64)
    @Comment("凭证明文哈希(SHA-256)")
    private String keyHash;

    @Column(name = "enabled", nullable = false)
    @Comment("是否启用")
    private boolean enabled = true;

    @Column(name = "expires_at")
    @Comment("过期时间(为空表示长期有效)")
    private Instant expiresAt;

    @Column(name = "last_used_at")
    @Comment("最近使用时间")
    private Instant lastUsedAt;

    @Column(name = "revoked_at")
    @Comment("吊销时间(非空即已吊销)")
    private Instant revokedAt;

    @Column(name = "created_at", nullable = false)
    @Comment("创建时间")
    private Instant createdAt = Instant.now();

    protected McpApiKeyEntity() {
    }

    public McpApiKeyEntity(AppUser user, String name, String keyPrefix, String keyHash, Instant expiresAt) {
        this.user = user;
        this.name = name;
        this.keyPrefix = keyPrefix;
        this.keyHash = keyHash;
        this.expiresAt = expiresAt;
    }

    public boolean isActive(Instant now) {
        return enabled
                && revokedAt == null
                && (expiresAt == null || expiresAt.isAfter(now));
    }

    public Long getId() {
        return id;
    }

    public AppUser getUser() {
        return user;
    }

    public String getName() {
        return name;
    }

    public String getKeyPrefix() {
        return keyPrefix;
    }

    public String getKeyHash() {
        return keyHash;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getLastUsedAt() {
        return lastUsedAt;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public void markRevoked(Instant revokedAt) {
        this.revokedAt = revokedAt;
        this.enabled = false;
    }

    public void markUsed(Instant lastUsedAt) {
        this.lastUsedAt = lastUsedAt;
    }
}
