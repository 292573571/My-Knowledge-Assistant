package com.example.workbench.modelconfig;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.example.workbench.auth.AdminAuthorizationService;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class AiModelServiceTest {

    @Test
    void rejectsLocalhostUnlessExplicitlyEnabled() {
        AiModelService service = service("development", "", false, "");

        assertThatThrownBy(() -> service.testPersonalConfig(null,
                request("http://localhost:8080", "key")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("localhost");
    }

    @Test
    void requiresHttpsInProductionBeforeMakingRequest() {
        AiModelService service = service("production", "", true, "");

        assertThatThrownBy(() -> service.testPersonalConfig(null,
                request("http://example.com", "key")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("HTTPS");
    }

    @Test
    void rejectsUnresolvedHost() {
        AiModelService service = service("development", "false", false, "");

        assertThatThrownBy(() -> service.testPersonalConfig(null,
                request("https://this-host-does-not-exist.invalid", "key")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("无法解析");
    }

    private AiModelService service(String environment, String requireHttps, boolean allowLocalhost,
                                   String allowedHosts) {
        return new AiModelService(mock(AiModelRepository.class), mock(AdminAuthorizationService.class),
                mock(ModelClientFactory.class), environment, requireHttps, allowLocalhost, allowedHosts);
    }

    private AiModelRequest request(String baseUrl, String apiKey) {
        return new AiModelRequest("test", baseUrl, apiKey, "model", null,
                null, null, null, null, null, false, true);
    }
}
