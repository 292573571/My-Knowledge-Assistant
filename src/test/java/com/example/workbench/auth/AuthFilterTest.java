package com.example.workbench.auth;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class AuthFilterTest {

    @Test
    void authenticatesWithHttpOnlySessionCookieValue() throws Exception {
        AuthService authService = mock(AuthService.class);
        AppUser user = new AppUser("alice", "Alice", "hash");
        when(authService.authenticate("cookie-token")).thenReturn(user);
        AuthFilter filter = new AuthFilter(authService, new ObjectMapper());
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/auth/me");
        request.setCookies(new Cookie(AuthFilter.SESSION_COOKIE, "cookie-token"));
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        verify(authService).authenticate("cookie-token");
        verify(chain).doFilter(eq(request), any());
    }

    @Test
    void rejectsLegacyQueryToken() throws Exception {
        AuthService authService = mock(AuthService.class);
        when(authService.authenticate(null)).thenThrow(new InvalidCredentialsException("authentication is required"));
        AuthFilter filter = new AuthFilter(authService, new ObjectMapper());
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/workbench/chat/stream");
        request.setParameter("access_token", "legacy-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(authService).authenticate(null);
        verifyNoInteractions(chain);
        org.assertj.core.api.Assertions.assertThat(response.getStatus()).isEqualTo(401);
    }
}
