package com.example.workbench.auth;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.workbench.config.GlobalExceptionHandler;
import com.example.workbench.modelconfig.ModelConfigContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * AuthFilter 的 HTTP 契约。
 *
 * <p>这是全站唯一的安全边界，但此前项目里没有任何 MockMvc 用例，白名单改错、
 * 401 响应体结构变化、Cookie/Header 优先级变化都只能等线上发现。这里用
 * standalone MockMvc 直接压 filter 本身，不启动 Spring 容器。</p>
 */
class AuthFilterHttpContractTest {

    private AuthService authService;
    private ModelConfigContext modelConfigContext;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        authService = mock(AuthService.class);
        modelConfigContext = mock(ModelConfigContext.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new ProbeController())
                .addFilters(new AuthFilter(authService, new ObjectMapper(), modelConfigContext))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("未登录访问 /api/ 返回 401 且响应体是 {error}")
    void unauthenticatedApiRequestReturns401WithErrorBody() throws Exception {
        when(authService.authenticate(any())).thenThrow(new InvalidCredentialsException("登录状态已失效，请重新登录"));

        mockMvc.perform(get("/api/probe"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.error").value("登录状态已失效，请重新登录"));

        verify(modelConfigContext).clear();
    }

    @Test
    @DisplayName("Bearer Token 通过后注入登录用户并绑定模型上下文")
    void bearerTokenAuthenticatesAndPopulatesContext() throws Exception {
        AppUser user = user(7L, "u_7");
        when(authService.authenticate("token-abc")).thenReturn(user);

        mockMvc.perform(get("/api/probe").header(HttpHeaders.AUTHORIZATION, "Bearer token-abc"))
                .andExpect(status().isOk())
                .andExpect(content().string("user:u_7"));

        verify(modelConfigContext).set(7L);
        verify(modelConfigContext).clear();
    }

    @Test
    @DisplayName("会话 Cookie 同样可以完成认证")
    void sessionCookieAuthenticates() throws Exception {
        AppUser user = user(9L, "u_9");
        when(authService.authenticate("cookie-token")).thenReturn(user);

        mockMvc.perform(get("/api/probe").cookie(new Cookie(AuthFilter.SESSION_COOKIE, "cookie-token")))
                .andExpect(status().isOk())
                .andExpect(content().string("user:u_9"));
    }

    @Test
    @DisplayName("Cookie 与 Authorization 同时存在时以 Cookie 为准")
    void cookieTakesPrecedenceOverBearerHeader() throws Exception {
        AppUser user = user(9L, "u_9");
        when(authService.authenticate("cookie-token")).thenReturn(user);

        mockMvc.perform(get("/api/probe")
                        .cookie(new Cookie(AuthFilter.SESSION_COOKIE, "cookie-token"))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer header-token"))
                .andExpect(status().isOk());

        verify(authService).authenticate("cookie-token");
        verify(authService, never()).authenticate("header-token");
    }

    @Test
    @DisplayName("/api/health 是公开路径，不触发认证")
    void healthEndpointIsPublic() throws Exception {
        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(content().string("ok"));

        verify(authService, never()).authenticate(any());
    }

    @Test
    @DisplayName("/api/eval/gate 是公开路径（发版门禁依赖它），不触发认证")
    void evalGateEndpointIsPublic() throws Exception {
        mockMvc.perform(get("/api/eval/gate"))
                .andExpect(status().isOk())
                .andExpect(content().string("gate"));

        verify(authService, never()).authenticate(any());
    }

    @Test
    @DisplayName("非 /api/ 前缀的路径不经过 AuthFilter —— 记录已知边界")
    void nonApiPathBypassesFilterEntirely() throws Exception {
        // 这条不是「期望行为」，而是把现状钉住：AuthFilter.shouldNotFilter 只拦 /api/，
        // 所以挂在根路径下的端点（如 /mcp/**）默认完全不设防。改动前请先确认是否有意为之。
        mockMvc.perform(get("/mcp/probe"))
                .andExpect(status().isOk())
                .andExpect(content().string("mcp"));

        verify(authService, never()).authenticate(any());
    }

    @Test
    @DisplayName("静态资源路径不经过 AuthFilter")
    void staticResourceBypassesFilter() throws Exception {
        mockMvc.perform(get("/index.html"))
                .andExpect(status().isOk())
                .andExpect(content().string("index"));

        verify(authService, never()).authenticate(any());
    }

    @Test
    @DisplayName("认证失败时不写响应 Cookie，避免把坏会话粘住")
    void failedAuthenticationDoesNotSetCookie() throws Exception {
        when(authService.authenticate(any())).thenThrow(new InvalidCredentialsException("会话已过期"));

        mockMvc.perform(get("/api/probe").header(HttpHeaders.AUTHORIZATION, "Bearer stale"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));
    }

    private AppUser user(Long id, String publicId) {
        AppUser user = mock(AppUser.class);
        when(user.getId()).thenReturn(id);
        when(user.getPublicId()).thenReturn(publicId);
        return user;
    }

    @RestController
    static class ProbeController {

        @GetMapping("/api/probe")
        String probe(HttpServletRequest request) {
            AppUser user = (AppUser) request.getAttribute(AuthFilter.AUTHENTICATED_USER_ATTRIBUTE);
            return user == null ? "anonymous" : "user:" + user.getPublicId();
        }

        @GetMapping("/api/health")
        String health() {
            return "ok";
        }

        @GetMapping("/api/eval/gate")
        String gate() {
            return "gate";
        }

        @GetMapping("/mcp/probe")
        String mcp() {
            return "mcp";
        }

        @GetMapping("/index.html")
        String index() {
            return "index";
        }
    }
}
