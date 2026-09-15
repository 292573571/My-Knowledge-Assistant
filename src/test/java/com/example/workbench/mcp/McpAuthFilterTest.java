package com.example.workbench.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.workbench.auth.AppUser;
import com.example.workbench.auth.AuthFilter;
import com.example.workbench.auth.AuthService;
import com.example.workbench.auth.InvalidCredentialsException;
import com.example.workbench.config.McpConfig;
import com.example.workbench.modelconfig.ModelConfigContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class McpAuthFilterTest {

    private AuthService authService;
    private McpApiKeyService apiKeyService;
    private ModelConfigContext modelConfigContext;

    @BeforeEach
    void setUp() {
        authService = mock(AuthService.class);
        apiKeyService = mock(McpApiKeyService.class);
        modelConfigContext = mock(ModelConfigContext.class);
        McpRequestContext.clear();
    }

    private McpAuthFilter filter(boolean enabled) {
        return new McpAuthFilter(authService, apiKeyService, modelConfigContext,
                new ObjectMapper(), new McpConfig(enabled, ""));
    }

    @Test
    void rejectsMcpRequestWithoutAnyCredential() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter(true).doFilter(new MockHttpServletRequest("POST", "/mcp"), response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        verifyNoInteractions(chain);
    }

    @Test
    void acceptsValidMcpApiKeyAndExposesUserToTools() throws Exception {
        AppUser user = new AppUser("alice", "Alice", "hash");
        when(apiKeyService.authenticate(eq("mcp_valid-key"), any(Instant.class))).thenReturn(Optional.of(user));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/mcp");
        request.addHeader("Authorization", "Bearer mcp_valid-key");
        FilterChain chain = mock(FilterChain.class);

        filter(true).doFilter(request, new MockHttpServletResponse(), chain);

        verify(chain).doFilter(eq(request), any());
        assertThat(request.getAttribute(AuthFilter.AUTHENTICATED_USER_ATTRIBUTE)).isSameAs(user);
        verify(modelConfigContext).set(user.getId());
    }

    @Test
    void fallsBackToSessionTokenForNonApiKeyCredential() throws Exception {
        AppUser user = new AppUser("bob", "Bob", "hash");
        when(authService.authenticate("session-token")).thenReturn(user);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/mcp");
        request.addHeader("Authorization", "Bearer session-token");
        FilterChain chain = mock(FilterChain.class);

        filter(true).doFilter(request, new MockHttpServletResponse(), chain);

        verify(authService).authenticate("session-token");
        verify(chain).doFilter(eq(request), any());
        verify(apiKeyService, never()).authenticate(anyString(), any());
    }

    @Test
    void rejectsUnknownOrRevokedApiKey() throws Exception {
        when(apiKeyService.authenticate(eq("mcp_unknown"), any(Instant.class))).thenReturn(Optional.empty());
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/mcp");
        request.addHeader("Authorization", "Bearer mcp_unknown");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter(true).doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        verifyNoInteractions(chain);
    }

    @Test
    void rejectsExpiredSessionToken() throws Exception {
        when(authService.authenticate("expired")).thenThrow(new InvalidCredentialsException("会话已过期"));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/mcp");
        request.addHeader("Authorization", "Bearer expired");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter(true).doFilter(request, response, mock(FilterChain.class));

        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void doesNotTouchNonMcpEndpoints() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/workbench/documents");
        FilterChain chain = mock(FilterChain.class);

        filter(true).doFilter(request, new MockHttpServletResponse(), chain);

        verify(chain).doFilter(eq(request), any());
        verifyNoInteractions(authService, apiKeyService);
    }

    @Test
    void returnsNotFoundWhenMcpServerDisabled() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter(false).doFilter(new MockHttpServletRequest("POST", "/mcp"), response, chain);

        assertThat(response.getStatus()).isEqualTo(404);
        verifyNoInteractions(chain);
    }
}
