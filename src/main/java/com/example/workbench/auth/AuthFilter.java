package com.example.workbench.auth;

import com.example.workbench.config.ApiErrorResponse;
import com.example.workbench.modelconfig.ModelConfigContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.Cookie;
import java.io.IOException;
import java.util.Set;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class AuthFilter extends OncePerRequestFilter {

    public static final String AUTHENTICATED_USER_ATTRIBUTE = "authenticatedUser";
    public static final String AUTH_TOKEN_ATTRIBUTE = "authToken";
    public static final String SESSION_COOKIE = "workbench_session";
    private static final Set<String> PUBLIC_PATHS = Set.of("/api/auth/register", "/api/auth/login", "/api/auth/send-code", "/api/health");

    private final AuthService authService;
    private final ObjectMapper objectMapper;
    private final ModelConfigContext modelConfigContext;

    public AuthFilter(AuthService authService, ObjectMapper objectMapper, ModelConfigContext modelConfigContext) {
        this.authService = authService;
        this.objectMapper = objectMapper;
        this.modelConfigContext = modelConfigContext;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/") || PUBLIC_PATHS.contains(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        try {
            String token = token(request);
            AppUser user = authService.authenticate(token);
            request.setAttribute(AUTHENTICATED_USER_ATTRIBUTE, user);
            request.setAttribute(AUTH_TOKEN_ATTRIBUTE, token);
            modelConfigContext.set(user.getId());
            filterChain.doFilter(request, response);
        } catch (InvalidCredentialsException exception) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            objectMapper.writeValue(response.getOutputStream(), new ApiErrorResponse(exception.getMessage()));
        } finally {
            modelConfigContext.clear();
        }
    }

    private String token(HttpServletRequest request) {
        if (request.getCookies() != null) {
            for (Cookie cookie : request.getCookies()) {
                if (SESSION_COOKIE.equals(cookie.getName())) return cookie.getValue();
            }
        }
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authorization != null && authorization.startsWith("Bearer ")) {
            return authorization.substring("Bearer ".length()).trim();
        }
        return null;
    }
}
