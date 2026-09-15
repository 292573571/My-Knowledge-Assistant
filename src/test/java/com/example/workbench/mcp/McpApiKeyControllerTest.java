package com.example.workbench.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.workbench.auth.AppUser;
import com.example.workbench.auth.AuthFilter;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

class McpApiKeyControllerTest {

    private McpApiKeyService apiKeyService;
    private McpApiKeyController controller;
    private final AppUser user = new AppUser("alice", "Alice", "hash");

    @BeforeEach
    void setUp() {
        apiKeyService = mock(McpApiKeyService.class);
        controller = new McpApiKeyController(apiKeyService);
    }

    private MockHttpServletRequest requestWithUser() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(AuthFilter.AUTHENTICATED_USER_ATTRIBUTE, user);
        return request;
    }

    @Test
    void issuesKeyAndReturnsPlaintextOnce() {
        when(apiKeyService.issue(eq(user), eq("cursor"), any(Duration.class)))
                .thenAnswer(invocation -> {
                    McpApiKeyEntity entity = new McpApiKeyEntity(user, "cursor", "mcp_abc", "hash-value",
                            Instant.now().plus(Duration.ofDays(30)));
                    return new McpApiKeyService.IssuedKey("mcp_plaintext-token", entity);
                });

        McpApiKeyIssuedResponse response =
                controller.create(new McpApiKeyController.CreateRequest("cursor", 30), requestWithUser());

        assertThat(response.plaintext()).isEqualTo("mcp_plaintext-token");
        assertThat(response.key().name()).isEqualTo("cursor");
        assertThat(response.key().keyPrefix()).isEqualTo("mcp_abc");
    }

    @Test
    void neverExposesKeyHashInResponse() {
        McpApiKeyEntity entity = new McpApiKeyEntity(user, "cursor", "mcp_abc", "secret-hash", null);
        when(apiKeyService.list(user)).thenReturn(List.of(entity));

        List<McpApiKeyResponse> keys = controller.list(requestWithUser());

        assertThat(keys).hasSize(1);
        assertThat(keys.get(0).toString()).doesNotContain("secret-hash");
    }

    @Test
    void revokesOwnKey() {
        when(apiKeyService.revoke(eq(user), eq(7L), any(Instant.class))).thenReturn(true);

        ResponseEntity<Void> response = controller.revoke(7L, requestWithUser());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    void returnsNotFoundWhenRevokingUnknownKey() {
        when(apiKeyService.revoke(eq(user), anyLong(), any(Instant.class))).thenReturn(false);

        ResponseEntity<Void> response = controller.revoke(999L, requestWithUser());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void rejectsUnauthenticatedManagementCalls() {
        MockHttpServletRequest anonymous = new MockHttpServletRequest();

        assertThatThrownBy(() -> controller.list(anonymous))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode())
                        .isEqualTo(HttpStatus.UNAUTHORIZED));
    }
}
