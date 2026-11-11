package com.example.workbench.auth;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.workbench.audit.AuditService;
import com.example.workbench.config.GlobalExceptionHandler;
import java.time.Instant;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * AuthController 的 HTTP 契约：状态码、Set-Cookie 属性、错误体结构、参数校验文案。
 *
 * <p>登录接口此前 0 行覆盖。这里锁住的是「前端依赖的约定」，不是内部实现：
 * Cookie 名、HttpOnly、SameSite、Secure、失败时的 401 与 {error} 结构。</p>
 */
class AuthControllerHttpContractTest {

    private AuthService authService;
    private UserProfileService userProfileService;
    private AdminAuthorizationService adminAuthorizationService;
    private EmailVerificationService emailVerificationService;
    private AuditService auditService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        authService = mock(AuthService.class);
        userProfileService = mock(UserProfileService.class);
        adminAuthorizationService = mock(AdminAuthorizationService.class);
        emailVerificationService = mock(EmailVerificationService.class);
        auditService = mock(AuditService.class);
        when(adminAuthorizationService.effectiveRole(anyString(), any())).thenReturn(SystemRole.USER);

        AuthController controller = new AuthController(authService, userProfileService,
                adminAuthorizationService, emailVerificationService, auditService, true, 168L);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("登录成功返回 200、用户信息与带安全属性的会话 Cookie")
    void loginReturnsUserAndSecureCookie() throws Exception {
        when(authService.login(any(LoginRequest.class), anyString())).thenReturn(new AuthResponse(
                "token-abc", "alice", "alice@example.com", null, "爱丽丝", "u_alice",
                "/api/auth/avatar", Instant.parse("2026-01-01T00:00:00Z"), SystemRole.ADMIN));

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"account\":\"alice\",\"password\":\"secret\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.account").value("alice"))
                .andExpect(jsonPath("$.publicId").value("u_alice"))
                .andExpect(jsonPath("$.systemRole").value("USER"))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, Matchers.allOf(
                        Matchers.containsString(AuthFilter.SESSION_COOKIE + "=token-abc"),
                        Matchers.containsString("HttpOnly"),
                        Matchers.containsString("SameSite=Strict"),
                        Matchers.containsString("Secure"))));
    }

    @Test
    @DisplayName("账号或密码错误返回 401 且不下发会话 Cookie")
    void loginFailureReturns401WithoutCookie() throws Exception {
        when(authService.login(any(LoginRequest.class), anyString()))
                .thenThrow(new InvalidCredentialsException("账号或密码不正确"));

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"account\":\"alice\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("账号或密码不正确"))
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));
    }

    @Test
    @DisplayName("账号为空时返回 400 并透出校验文案")
    void blankAccountReturns400() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"account\":\"  \",\"password\":\"secret\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("账号不能为空"));
    }

    @Test
    @DisplayName("注册成功返回 201")
    void registerReturns201() throws Exception {
        when(authService.register(any(RegisterRequest.class))).thenReturn(new RegisterResultResponse("注册成功"));

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"bob@example.com\",\"code\":\"123456\",\"password\":\"secret123\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.message").value("注册成功"));
    }

    @Test
    @DisplayName("/me 从请求属性读取登录用户，不重新查库")
    void meReadsAuthenticatedUserFromRequestAttribute() throws Exception {
        AppUser user = mock(AppUser.class);
        when(user.getAccount()).thenReturn("alice");
        when(user.getPublicId()).thenReturn("u_alice");
        when(adminAuthorizationService.effectiveRole(user)).thenReturn(SystemRole.ADMIN);

        mockMvc.perform(get("/api/auth/me").requestAttr(AuthFilter.AUTHENTICATED_USER_ATTRIBUTE, user))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.account").value("alice"))
                .andExpect(jsonPath("$.systemRole").value("ADMIN"));
    }

    @Test
    @DisplayName("登出返回 204 并清空会话 Cookie（Max-Age=0）")
    void logoutClearsCookie() throws Exception {
        AppUser user = mock(AppUser.class);
        when(user.getPublicId()).thenReturn("u_alice");

        mockMvc.perform(post("/api/auth/logout")
                        .requestAttr(AuthFilter.AUTHENTICATED_USER_ATTRIBUTE, user)
                        .requestAttr(AuthFilter.AUTH_TOKEN_ATTRIBUTE, "token-abc"))
                .andExpect(status().isNoContent())
                .andExpect(header().string(HttpHeaders.SET_COOKIE,
                        Matchers.allOf(Matchers.containsString(AuthFilter.SESSION_COOKIE + "="),
                                Matchers.containsString("Max-Age=0"))));

        verify(authService).logout(eq("token-abc"));
    }
}
