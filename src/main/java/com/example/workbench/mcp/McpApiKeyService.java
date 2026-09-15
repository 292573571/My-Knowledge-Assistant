package com.example.workbench.mcp;

import com.example.workbench.auth.AppUser;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * MCP 客户端长期凭证的签发与校验。
 *
 * <p>明文凭证格式为 {@code mcp_<32 字节随机串的 base64url>}，仅在签发时返回一次；
 * 库中只保存 SHA-256 哈希，校验时按哈希等值查询，不做可逆解密。
 */
@Service
public class McpApiKeyService {

    public static final String KEY_PREFIX = "mcp_";

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int RANDOM_BYTES = 32;
    private static final Duration LAST_USED_THROTTLE = Duration.ofMinutes(1);

    private final McpApiKeyRepository repository;

    public McpApiKeyService(McpApiKeyRepository repository) {
        this.repository = repository;
    }

    /** 签发新凭证，返回仅此一次可见的明文。 */
    @Transactional
    public IssuedKey issue(AppUser user, String name, Duration timeToLive) {
        String plaintext = KEY_PREFIX + randomToken();
        Instant now = Instant.now();
        McpApiKeyEntity entity = new McpApiKeyEntity(
                user,
                normalizeName(name),
                plaintext.substring(0, Math.min(12, plaintext.length())),
                sha256Hex(plaintext),
                timeToLive == null ? null : now.plus(timeToLive));
        McpApiKeyEntity saved = repository.save(entity);
        return new IssuedKey(plaintext, saved);
    }

    /**
     * 校验 Bearer 凭证。仅处理 {@code mcp_} 前缀，非本类型凭证返回空，
     * 交由调用方回退到会话令牌。
     */
    @Transactional
    public Optional<AppUser> authenticate(String bearerToken, Instant now) {
        if (bearerToken == null || !bearerToken.startsWith(KEY_PREFIX)) {
            return Optional.empty();
        }
        Optional<McpApiKeyEntity> found = repository.findByKeyHash(sha256Hex(bearerToken));
        if (found.isEmpty()) {
            return Optional.empty();
        }
        McpApiKeyEntity entity = found.get();
        if (!entity.isActive(now)) {
            return Optional.empty();
        }
        Instant lastUsed = entity.getLastUsedAt();
        if (lastUsed == null || lastUsed.plus(LAST_USED_THROTTLE).isBefore(now)) {
            entity.markUsed(now);
            repository.save(entity);
        }
        return Optional.ofNullable(entity.getUser());
    }

    @Transactional(readOnly = true)
    public List<McpApiKeyEntity> list(AppUser user) {
        return repository.findAllByUserOrderByCreatedAtDesc(user);
    }

    @Transactional
    public boolean revoke(AppUser user, Long keyId, Instant now) {
        Optional<McpApiKeyEntity> found = repository.findById(keyId);
        if (found.isEmpty()) {
            return false;
        }
        McpApiKeyEntity entity = found.get();
        AppUser owner = entity.getUser();
        if (owner == null || user == null || !owner.getId().equals(user.getId())) {
            return false;
        }
        entity.markRevoked(now);
        repository.save(entity);
        return true;
    }

    private static String normalizeName(String name) {
        String trimmed = name == null ? "" : name.strip();
        if (trimmed.isEmpty()) {
            return "MCP 客户端";
        }
        return trimmed.length() > 100 ? trimmed.substring(0, 100) : trimmed;
    }

    private static String randomToken() {
        byte[] bytes = new byte[RANDOM_BYTES];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String sha256Hex(String plaintext) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(plaintext.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(hashed.length * 2);
            for (byte b : hashed) {
                builder.append(Character.forDigit((b >> 4) & 0xF, 16));
                builder.append(Character.forDigit(b & 0xF, 16));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    /** 签发结果：明文仅在创建响应中出现一次。 */
    public record IssuedKey(String plaintext, McpApiKeyEntity entity) {
    }
}
