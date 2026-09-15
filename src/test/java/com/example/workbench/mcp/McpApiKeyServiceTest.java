package com.example.workbench.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.workbench.auth.AppUser;
import com.example.workbench.auth.AppUserRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class McpApiKeyServiceTest {

    private final McpApiKeyRepository repository = mock(McpApiKeyRepository.class);
    private final AppUserRepository userRepository = mock(AppUserRepository.class);
    private final McpApiKeyService service = new McpApiKeyService(repository, userRepository);
    private final AppUser user = new AppUser("alice", "Alice", "hash");

    @Test
    void issuedPlaintextUsesMcpPrefixAndIsHashedAtRest() {
        when(repository.save(any(McpApiKeyEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        McpApiKeyService.IssuedKey issued = service.issue(user, "claude-desktop", null);

        assertThat(issued.plaintext()).startsWith("mcp_");
        assertThat(issued.entity().getKeyHash()).isEqualTo(McpApiKeyService.sha256Hex(issued.plaintext()));
        assertThat(issued.entity().getKeyHash()).doesNotContain(issued.plaintext());
        assertThat(issued.entity().getName()).isEqualTo("claude-desktop");
        assertThat(issued.entity().isEnabled()).isTrue();
    }

    @Test
    void authenticatesByPlaintextKey() {
        McpApiKeyService.IssuedKey issued = issueAndStore();

        assertThat(service.authenticate(issued.plaintext(), Instant.now())).contains(user);
    }

    @Test
    void ignoresNonMcpCredentials() {
        assertThat(service.authenticate("session-token", Instant.now())).isEmpty();
        assertThat(service.authenticate(null, Instant.now())).isEmpty();
    }

    @Test
    void rejectsRevokedKey() {
        McpApiKeyService.IssuedKey issued = issueAndStore();
        issued.entity().markRevoked(Instant.now());

        assertThat(service.authenticate(issued.plaintext(), Instant.now())).isEmpty();
    }

    @Test
    void rejectsExpiredKey() {
        McpApiKeyService.IssuedKey issued = issueAndStore(Duration.ofHours(1));
        Instant later = Instant.now().plus(Duration.ofHours(2));

        assertThat(service.authenticate(issued.plaintext(), later)).isEmpty();
    }

    /**
     * 回归锁：MCP 工具在无事务链路里访问凭证实体的 LAZY user 代理会抛
     * LazyInitializationException(项目关闭了 open-in-view)。认证必须返回按主键
     * 重新加载的实体，保证下游工具能安全读取用户字段。
     */
    @Test
    void returnsReloadedUserInsteadOfDetachedLazyProxy() {
        AppUser reloaded = new AppUser("alice", "Alice", "hash");
        McpApiKeyService.IssuedKey issued = issueAndStore();
        when(userRepository.findById(any())).thenReturn(Optional.of(reloaded));

        assertThat(service.authenticate(issued.plaintext(), Instant.now())).containsSame(reloaded);
    }

    @Test
    void rejectsUnknownKey() {
        when(repository.findByKeyHash(anyString())).thenReturn(Optional.empty());

        assertThat(service.authenticate("mcp_does-not-exist", Instant.now())).isEmpty();
    }

    @Test
    void throttlesLastUsedUpdate() {
        McpApiKeyService.IssuedKey issued = issueAndStore();
        org.mockito.Mockito.clearInvocations(repository);
        Instant now = Instant.now();
        service.authenticate(issued.plaintext(), now);
        service.authenticate(issued.plaintext(), now.plusSeconds(5));

        // 节流窗口内只写一次 last_used_at，后续请求不重复落库。
        org.mockito.Mockito.verify(repository, org.mockito.Mockito.times(1)).save(any(McpApiKeyEntity.class));
    }

    private McpApiKeyService.IssuedKey issueAndStore() {
        return issueAndStore(null);
    }

    private McpApiKeyService.IssuedKey issueAndStore(Duration timeToLive) {
        when(repository.save(any(McpApiKeyEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        McpApiKeyService.IssuedKey issued = service.issue(user, "client", timeToLive);
        when(repository.findByKeyHash(issued.entity().getKeyHash())).thenReturn(Optional.of(issued.entity()));
        // 生产环境这里返回的是按主键重新加载的实体(而非 LAZY 代理),测试对齐该行为。
        when(userRepository.findById(any())).thenReturn(Optional.of(user));
        return issued;
    }
}
