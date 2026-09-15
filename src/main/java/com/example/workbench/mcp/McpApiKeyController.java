package com.example.workbench.mcp;

import com.example.workbench.auth.AppUser;
import com.example.workbench.auth.AuthFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * MCP 凭证自助管理：签发、列表、吊销。
 *
 * <p>路由在 {@code /api/} 下，复用现有会话鉴权；签发出的 {@code mcp_} 前缀长期凭证
 * 供 MCP 客户端调用 {@code /mcp} 端点使用。
 */
@RestController
@RequestMapping("/api/mcp/keys")
public class McpApiKeyController {

    private final McpApiKeyService apiKeyService;

    public McpApiKeyController(McpApiKeyService apiKeyService) {
        this.apiKeyService = apiKeyService;
    }

    public record CreateRequest(String name, Integer ttlDays) {
    }

    @PostMapping
    public McpApiKeyIssuedResponse create(@RequestBody(required = false) CreateRequest request,
                                          HttpServletRequest http) {
        AppUser user = authenticatedUser(http);
        String name = request == null ? null : request.name();
        Integer ttlDays = request == null ? null : request.ttlDays;
        Duration timeToLive = ttlDays == null || ttlDays <= 0 ? null : Duration.ofDays(Math.min(3650, ttlDays));
        McpApiKeyService.IssuedKey issued = apiKeyService.issue(user, name, timeToLive);
        return new McpApiKeyIssuedResponse(McpApiKeyResponse.from(issued.entity()), issued.plaintext());
    }

    @GetMapping
    public List<McpApiKeyResponse> list(HttpServletRequest http) {
        return apiKeyService.list(authenticatedUser(http)).stream()
                .map(McpApiKeyResponse::from)
                .toList();
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> revoke(@PathVariable Long id, HttpServletRequest http) {
        boolean revoked = apiKeyService.revoke(authenticatedUser(http), id, Instant.now());
        return revoked ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    private static AppUser authenticatedUser(HttpServletRequest request) {
        AppUser user = (AppUser) request.getAttribute(AuthFilter.AUTHENTICATED_USER_ATTRIBUTE);
        if (user == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "authentication is required");
        }
        return user;
    }
}
