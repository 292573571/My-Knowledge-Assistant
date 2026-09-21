package com.example.workbench.learningassistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.example.workbench.conversation.ConversationExecutionRegistry;
import com.example.workbench.conversation.ConversationService;
import com.example.workbench.config.ModelProviderException;
import com.example.workbench.modelconfig.ModelConfigContext;
import com.example.workbench.streaming.StreamSessionStore;
import java.net.ConnectException;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

class LearningAssistantControllerTest {
    private final LearningAssistantController controller = new LearningAssistantController(
            mock(LearningAssistantService.class),
            mock(ConversationExecutionRegistry.class),
            mock(ConversationService.class),
            mock(ModelConfigContext.class),
            mock(StreamSessionStore.class),
            15_000L);

    @AfterEach
    void shutdown() {
        controller.shutdownHeartbeat();
    }

    @Test
    void preservesProviderDetailsWhenExceptionIsWrapped() {
        ModelProviderException exception = ModelProviderException.authError("trace-auth");

        Map<String, Object> payload = errorPayload(new CompletionException(exception));

        assertThat(payload.get("message")).isEqualTo(exception.getUserMessage());
        assertThat(payload.get("errorType")).isEqualTo("auth_error");
        assertThat(payload.get("status")).isEqualTo(401);
        assertThat(payload.get("retryable")).isEqualTo(false);
        assertThat(payload.get("traceId")).isEqualTo("trace-auth");
        assertThat(payload.get("requestId")).isEqualTo("request-1");
    }

    @Test
    void classifiesWrappedTimeoutAsRetryable() {
        Map<String, Object> payload = errorPayload(new CompletionException(new TimeoutException("timed out")));

        assertThat(payload.get("errorType")).isEqualTo("timeout");
        assertThat(payload.get("retryable")).isEqualTo(true);
        assertThat(payload.get("requestId")).isEqualTo("request-1");
        assertThat(payload).doesNotContainKey("status");
        assertThat(payload.get("message")).asString().contains("超时");
    }

    @Test
    void preservesResponseStatusReasonAndRetryability() {
        ResponseStatusException exception = new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "请求过于频繁");

        Map<String, Object> payload = errorPayload(new CompletionException(exception));

        assertThat(payload.get("message")).isEqualTo("请求过于频繁");
        assertThat(payload.get("errorType")).isEqualTo("ResponseStatusException");
        assertThat(payload.get("status")).isEqualTo(429);
        assertThat(payload.get("retryable")).isEqualTo(true);
        assertThat(payload.get("requestId")).isEqualTo("request-1");
    }

    @Test
    void classifiesWrappedNetworkFailureAsRetryableModelFailure() {
        Map<String, Object> payload = errorPayload(new CompletionException(new ConnectException("connection refused")));

        assertThat(payload.get("errorType")).isEqualTo("model_service_unavailable");
        assertThat(payload.get("status")).isEqualTo(502);
        assertThat(payload.get("retryable")).isEqualTo(true);
        assertThat(payload.get("requestId")).isEqualTo("request-1");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> errorPayload(Throwable exception) {
        return (Map<String, Object>) org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                controller, "errorPayload", exception, "request-1");
    }
}
