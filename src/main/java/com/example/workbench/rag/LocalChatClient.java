package com.example.workbench.rag;

import com.example.workbench.memory.ChatMessage;
import com.example.workbench.tools.WebSearchResult;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import reactor.core.publisher.Flux;

@Component
public class LocalChatClient {

    private static final Logger log = LoggerFactory.getLogger(LocalChatClient.class);

    private final ChatClient chatClient;
    private final String chatBaseUrl;
    private final String chatModel;
    private final List<String> fallbackModels;
    private final int maxAttempts;
    private final Duration retryBackoff;
    private final Duration requestTimeout;
    private final Duration fallbackRequestTimeout;
    private final String fallbackStrategy;
    private final double temperature;

    public LocalChatClient(
            ObjectProvider<ChatClient> chatClientProvider,
            @Value("${spring.ai.openai.chat.base-url:${spring.ai.openai.base-url:unknown}}") String chatBaseUrl,
            @Value("${spring.ai.openai.chat.options.model:${app.ai.model:unknown}}") String chatModel,
            @Value("${app.ai.fallback-models:}") String fallbackModels,
            @Value("${app.ai.retry.max-attempts:2}") int maxAttempts,
            @Value("${app.ai.retry.backoff-ms:500}") long retryBackoffMs,
            @Value("${app.ai.request-timeout-ms:20000}") long requestTimeoutMs,
            @Value("${app.ai.fallback-request-timeout-ms:45000}") long fallbackRequestTimeoutMs,
            @Value("${app.ai.fallback-strategy:local-answer}") String fallbackStrategy,
            @Value("${app.ai.temperature:0.3}") double temperature
    ) {
        this.chatClient = chatClientProvider.getIfAvailable();
        this.chatBaseUrl = chatBaseUrl;
        this.chatModel = chatModel;
        this.fallbackModels = parseModels(fallbackModels);
        this.maxAttempts = Math.max(1, maxAttempts);
        this.retryBackoff = Duration.ofMillis(Math.max(0, retryBackoffMs));
        this.requestTimeout = Duration.ofMillis(Math.max(1, requestTimeoutMs));
        this.fallbackRequestTimeout = Duration.ofMillis(Math.max(1, fallbackRequestTimeoutMs));
        this.fallbackStrategy = fallbackStrategy == null || fallbackStrategy.isBlank() ? "local-answer" : fallbackStrategy;
        this.temperature = Math.max(0.0, Math.min(1.0, temperature));

        log.info(
                "AI client policy configured provider=openai-compatible primaryModel={} fallbackModels={} temperature={} primaryTimeoutMs={} fallbackTimeoutMs={} retryMaxAttempts={} retryBackoffMs={} fallbackStrategy={}",
                this.chatModel,
                this.fallbackModels,
                this.temperature,
                this.requestTimeout.toMillis(),
                this.fallbackRequestTimeout.toMillis(),
                this.maxAttempts,
                this.retryBackoff.toMillis(),
                this.fallbackStrategy
        );
    }

    public String call(
            String prompt,
            List<SourceDocument> sources,
            List<ChatMessage> history,
            Map<String, String> options
    ) {
        String aiAnswer = callSpringAi(prompt, options);

        if (aiAnswer != null && !aiAnswer.isBlank()) {
            return aiAnswer;
        }

        return sources.stream()
                .map(SourceDocument::content)
                .collect(Collectors.joining("\n\n"))
                + "\n\n来源："
                + sources.stream()
                .map(source -> source.source() + "#chunk-" + source.chunkIndex())
                .distinct()
                .collect(Collectors.joining(", "));
    }

    public String callWithWebResults(
            String prompt,
            List<WebSearchResult> webResults,
            List<ChatMessage> history,
            Map<String, String> options
    ) {
        String aiAnswer = callSpringAi(prompt, options);

        if (aiAnswer != null && !aiAnswer.isBlank()) {
            return "知识库没有足够信息，我将使用搜索工具...\n\n" + aiAnswer + "\n\n来自 Web";
        }

        return "知识库没有足够信息，我将使用搜索工具...\n\n"
                + webResults.stream()
                .map(result -> result.snippet() + "\n来源：" + result.title() + " - " + result.url())
                .collect(Collectors.joining("\n\n"))
                + "\n\n来自 Web";
    }

    public String generate(String prompt) {
        return callSpringAi(prompt, Map.of());
    }

    public Flux<String> stream(String prompt, Map<String, String> options) {
        if (chatClient == null) {
            return Flux.error(new IllegalStateException("ChatClient is not available"));
        }

        String conversationId = options.getOrDefault("conversationId", "default");
        AtomicBoolean receivedToken = new AtomicBoolean(false);
        return streamModel(prompt, conversationId, chatModel, requestTimeout)
                .doOnNext(token -> receivedToken.set(true))
                .onErrorResume(error -> !receivedToken.get() && !fallbackModels.isEmpty()
                        ? streamModel(prompt, conversationId, fallbackModels.get(0), fallbackRequestTimeout)
                        : Flux.error(error));
    }

    private Flux<String> streamModel(String prompt, String conversationId, String model, Duration timeout) {
        log.info("AI model stream started provider=openai-compatible model={} conversationId={} requestTimeoutMs={} promptLength={}",
                model, conversationId, timeout.toMillis(), prompt == null ? 0 : prompt.length());
        return chatClient.prompt()
                .user(prompt)
                .options(OpenAiChatOptions.builder().model(model).temperature(temperature).build())
                .advisors(advisorSpec -> advisorSpec.param("conversationId", conversationId))
                .stream()
                .content()
                .timeout(timeout)
                .doOnError(error -> log.warn("AI model stream failed model={} conversationId={} errorType={}", model, conversationId, error.getClass().getSimpleName()));
    }

    private String callSpringAi(String prompt, Map<String, String> options) {
        if (chatClient == null) {
            log.warn("AI model skipped reason=chatClient_not_available model={} baseUrl={}", chatModel, chatBaseUrl);
            return null;
        }

        String conversationId = options.getOrDefault("conversationId", "default");
        List<String> models = modelsToTry();
        log.info(
                "AI model call policy provider=openai-compatible primaryModel={} fallbackModels={} baseUrl={} conversationId={} temperature={} primaryTimeoutMs={} fallbackTimeoutMs={} retryMaxAttempts={} retryBackoffMs={} fallbackStrategy={} promptLength={}",
                chatModel,
                fallbackModels,
                chatBaseUrl,
                conversationId,
                temperature,
                requestTimeout.toMillis(),
                fallbackRequestTimeout.toMillis(),
                maxAttempts,
                retryBackoff.toMillis(),
                fallbackStrategy,
                prompt == null ? 0 : prompt.length()
        );

        for (int modelIndex = 0; modelIndex < models.size(); modelIndex++) {
            String model = models.get(modelIndex);
            Duration timeout = modelIndex == 0 ? requestTimeout : fallbackRequestTimeout;

            for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                long startedAt = System.currentTimeMillis();
                log.info(
                        "AI model call started provider=openai-compatible model={} modelRole={} baseUrl={} conversationId={} attempt={} maxAttempts={} requestTimeoutMs={} promptLength={}",
                        model,
                        modelIndex == 0 ? "primary" : "fallback",
                        chatBaseUrl,
                        conversationId,
                        attempt,
                        maxAttempts,
                        timeout.toMillis(),
                        prompt == null ? 0 : prompt.length()
                );

                try {
                    ChatResponse response = callChatResponse(prompt, conversationId, model, timeout);
                    String content = content(response);
                    logModelResponse(response, model, conversationId, attempt, startedAt, content);
                    return content;
                } catch (RuntimeException exception) {
                    boolean retryable = logModelError(exception, model, conversationId, attempt, startedAt, modelIndex < models.size() - 1);

                    if (retryable && attempt < maxAttempts) {
                        sleepBackoff(model, conversationId, attempt);
                    } else if (!retryable) {
                        break;
                    }
                }
            }
        }

        log.warn(
                "AI model fallback selected strategy={} provider=openai-compatible primaryModel={} fallbackModels={} conversationId={} result=local_answer",
                fallbackStrategy,
                chatModel,
                fallbackModels,
                conversationId
        );
        return null;
    }

    private ChatResponse callChatResponse(String prompt, String conversationId, String model, Duration timeout) {
        CompletableFuture<ChatResponse> future = CompletableFuture.supplyAsync(() -> chatClient.prompt()
                .user(prompt)
                .options(OpenAiChatOptions.builder().model(model).temperature(temperature).build())
                .advisors(advisorSpec -> advisorSpec.param(
                        "conversationId",
                        conversationId
                ))
                .call()
                .chatResponse());

        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException exception) {
            future.cancel(true);
            throw new AiModelCallException("AI model request timed out after " + timeout.toMillis() + "ms", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AiModelCallException("AI model request interrupted", exception);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new AiModelCallException("AI model request failed", cause);
        }
    }

    private void logModelResponse(ChatResponse response, String model, String conversationId, int attempt, long startedAt, String content) {
        ChatResponseMetadata responseMetadata = response == null ? null : response.getMetadata();
        Usage usage = responseMetadata == null ? null : responseMetadata.getUsage();
        Generation result = response == null ? null : response.getResult();
        String finishReason = result == null || result.getMetadata() == null
                ? "unknown"
                : result.getMetadata().getFinishReason();

        log.info(
                "AI model call completed provider=openai-compatible model={} responseModel={} responseId={} conversationId={} attempt={} finishReason={} promptTokens={} completionTokens={} totalTokens={} answerLength={} durationMs={}",
                model,
                responseMetadata == null ? "unknown" : responseMetadata.getModel(),
                responseMetadata == null ? "unknown" : responseMetadata.getId(),
                conversationId,
                attempt,
                finishReason == null || finishReason.isBlank() ? "unknown" : finishReason,
                usage == null ? null : usage.getPromptTokens(),
                usage == null ? null : usage.getCompletionTokens(),
                usage == null ? null : usage.getTotalTokens(),
                content == null ? 0 : content.length(),
                System.currentTimeMillis() - startedAt
        );
    }

    private boolean logModelError(
            RuntimeException exception,
            String model,
            String conversationId,
            int attempt,
            long startedAt,
            boolean willSwitchModel
    ) {
        Throwable rootCause = rootCause(exception);
        HttpStatusCodeException statusException = findHttpStatusException(exception);
        Integer statusCode = statusException == null ? null : statusException.getStatusCode().value();
        String errorType = errorType(exception, rootCause, statusCode);
        boolean retryable = isRetryable(errorType, statusCode);

        log.warn(
                "AI model call failed provider=openai-compatible model={} conversationId={} attempt={} maxAttempts={} durationMs={} errorType={} httpStatus={} exceptionClass={} rootCauseClass={} retryable={} willRetry={} willSwitchModel={} fallbackStrategy={}",
                model,
                conversationId,
                attempt,
                maxAttempts,
                System.currentTimeMillis() - startedAt,
                errorType,
                statusCode,
                exception.getClass().getSimpleName(),
                rootCause == null ? "unknown" : rootCause.getClass().getSimpleName(),
                retryable,
                retryable && attempt < maxAttempts,
                willSwitchModel && attempt == maxAttempts,
                fallbackStrategy
        );
        return retryable;
    }

    private String content(ChatResponse response) {
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
            return null;
        }

        return response.getResult().getOutput().getText();
    }

    private List<String> modelsToTry() {
        List<String> models = new ArrayList<>();
        models.add(chatModel);
        fallbackModels.stream()
                .filter(model -> models.stream().noneMatch(existing -> existing.equalsIgnoreCase(model)))
                .forEach(models::add);
        return models;
    }

    private List<String> parseModels(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }

        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(model -> !model.isBlank())
                .toList();
    }

    private void sleepBackoff(String model, String conversationId, int attempt) {
        if (retryBackoff.isZero()) {
            return;
        }

        try {
            log.info(
                    "AI model retry waiting model={} conversationId={} completedAttempt={} backoffMs={}",
                    model,
                    conversationId,
                    attempt,
                    retryBackoff.toMillis()
            );
            Thread.sleep(retryBackoff.toMillis());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private String errorType(RuntimeException exception, Throwable rootCause, Integer statusCode) {
        if (statusCode != null) {
            if (statusCode == 401 || statusCode == 403) {
                return "auth_error";
            }
            if (statusCode == 408) {
                return "timeout";
            }
            if (statusCode == 429) {
                return "rate_limited";
            }
            if (statusCode >= 500) {
                return "provider_server_error";
            }
            return "provider_client_error";
        }

        if (rootCause instanceof SocketTimeoutException || exception instanceof ResourceAccessException) {
            return "timeout_or_network_error";
        }

        if (rootCause instanceof TimeoutException) {
            return "timeout";
        }

        return "runtime_error";
    }

    private boolean isRetryable(String errorType, Integer statusCode) {
        if (statusCode != null) {
            return statusCode == 408 || statusCode == 429 || statusCode >= 500;
        }

        return "timeout_or_network_error".equals(errorType) || "runtime_error".equals(errorType);
    }

    private Throwable rootCause(Throwable throwable) {
        Throwable current = throwable;
        while (current != null && current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    private HttpStatusCodeException findHttpStatusException(Throwable throwable) {
        Throwable current = throwable;

        while (current != null) {
            if (current instanceof HttpStatusCodeException exception) {
                return exception;
            }
            current = current.getCause();
        }

        return null;
    }

    private String truncate(String value) {
        if (value == null || value.length() <= 1_000) {
            return value == null ? "" : value;
        }

        return value.substring(0, 1_000) + "...<truncated>";
    }

    private static class AiModelCallException extends RuntimeException {

        AiModelCallException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
