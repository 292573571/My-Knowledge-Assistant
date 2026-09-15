package com.example.workbench.mcp;

import com.example.workbench.auth.AppUser;
import com.example.workbench.auth.AuthFilter;
import com.example.workbench.auth.AuthService;
import com.example.workbench.auth.InvalidCredentialsException;
import com.example.workbench.config.ApiErrorResponse;
import com.example.workbench.config.LoggingContext;
import com.example.workbench.config.McpConfig;
import com.example.workbench.modelconfig.ModelConfigContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * MCP 端点鉴权。
 *
 * <p>Spring AI MCP Server 自动配置刻意不提供任何鉴权，且 {@link AuthFilter} 只拦截
 * {@code /api/} 前缀，因此 {@code /mcp/**} 必须自建安全边界，否则任何可达客户端都能
 * 枚举并调用全部工具。
 *
 * <p>双轨凭证：优先按 {@code mcp_} 前缀识别长期 API Key（MCP 客户端用），
 * 否则回退到现有会话令牌（网页端调试用）。
 */
@Component
public class McpAuthFilter extends OncePerRequestFilter {

    private static final Set<String> MCP_EXACT_PATHS = Set.of("/mcp", "/sse", "/mcp/message");
    private static final String MCP_PATH_PREFIX = "/mcp/";

    private final AuthService authService;
    private final McpApiKeyService apiKeyService;
    private final ModelConfigContext modelConfigContext;
    private final ObjectMapper objectMapper;
    private final McpConfig mcpConfig;

    public McpAuthFilter(
            AuthService authService,
            McpApiKeyService apiKeyService,
            ModelConfigContext modelConfigContext,
            ObjectMapper objectMapper,
            McpConfig mcpConfig
    ) {
        this.authService = authService;
        this.apiKeyService = apiKeyService;
        this.modelConfigContext = modelConfigContext;
        this.objectMapper = objectMapper;
        this.mcpConfig = mcpConfig;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !(MCP_EXACT_PATHS.contains(path) || path.startsWith(MCP_PATH_PREFIX));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (!mcpEnabled()) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        try {
            String token = token(request);
            AppUser user = authenticate(token);
            if (user == null) {
                unauthorized(response, "MCP 端点需要有效凭证：Authorization: Bearer <mcp_xxx 或会话令牌>");
                return;
            }
            request.setAttribute(AuthFilter.AUTHENTICATED_USER_ATTRIBUTE, user);
            request.setAttribute(AuthFilter.AUTH_TOKEN_ATTRIBUTE, token);
            LoggingContext.put(LoggingContext.USER_ID, user.getId());
            modelConfigContext.set(user.getId());
            McpRequestContext.set(user);
            filterChain.doFilter(request, response);
        } catch (InvalidCredentialsException exception) {
            unauthorized(response, exception.getMessage());
        } finally {
            McpRequestContext.clear();
            modelConfigContext.clear();
        }
    }

    /** API Key 优先，非 mcp_ 前缀回退会话令牌；两者都失败返回 null。 */
    AppUser authenticate(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        if (token.startsWith(McpApiKeyService.KEY_PREFIX)) {
            return apiKeyService.authenticate(token, Instant.now()).orElse(null);
        }
        try {
            return authService.authenticate(token);
        } catch (InvalidCredentialsException exception) {
            return null;
        }
    }

    private boolean mcpEnabled() {
        return mcpConfig != null && mcpConfig.enabled();
    }

    private static String token(HttpServletRequest request) {
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authorization != null && authorization.startsWith("Bearer ")) {
            return authorization.substring("Bearer ".length()).trim();
        }
        if (request.getCookies() != null) {
            for (Cookie cookie : request.getCookies()) {
                if (AuthFilter.SESSION_COOKIE.equals(cookie.getName())) {
                    return cookie.getValue();
                }
            }
        }
        return Optional.ofNullable(authorization).orElse(null);
    }

    private void unauthorized(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), new ApiErrorResponse(message));
    }
}
