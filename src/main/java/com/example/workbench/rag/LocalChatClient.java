package com.example.workbench.rag;

import com.example.workbench.config.ModelProviderException;
import com.example.workbench.memory.ChatMessage;
import com.example.workbench.modelconfig.ModelCircuitBreaker;
import com.example.workbench.modelconfig.ModelClientFactory;
import com.example.workbench.modelconfig.ModelConfigContext;
import com.example.workbench.modelconfig.ModelConfigService;
import com.example.workbench.modelconfig.ModelSpec;
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
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import reactor.core.publisher.Flux;

@Component
public class LocalChatClient {

    private static final Logger log = LoggerFactory.getLogger(LocalChatClient.class);

    /**
     * 模型厂商把错误当成正常内容返回时的高置信度特征（HTTP 200 但响应体实为错误报文）。
     * 仅命中这些模式才视为错误，避免把普通回答误判为报错。
     */
    private static final Pattern MODEL_ERROR_RATE_LIMIT = Pattern.compile(
            "(?i)(rate[\\s_-]?limit|too many requests|请求过于频繁|请求频率过高|频率限制|限流)");
    private static final Pattern MODEL_ERROR_QUOTA = Pattern.compile(
            "(?i)(quota|额度|余额不足|insufficient_quota|exceeded your quota|您的配额)");
    private static final Pattern MODEL_ERROR_BODY = Pattern.compile(
            "(?i)(\"error\"\\s*:|error_code|\"code\"\\s*:\\s*\"?\\d{3,}|错误码|error code|invalid_request_error)");
    private static final Pattern MODEL_ERROR_AUTH = Pattern.compile(
            "(?i)(\"type\"\\s*:\\s*\"invalid_request_error\"|authentication failed|api key 无效|鉴权失败|unauthorized|invalid api key)");

    private final ChatClient chatClient;
    private final String chatBaseUrl;
    private final String chatModel;
    private final List<String> fallbackModels;
    private final int maxAttempts;
    private final Duration retryBackoff;
    private final Duration requestTimeout;
    private final Duration fallbackRequestTimeout;
    private final Duration syncRequestTimeout;
    private final String fallbackStrategy;
    private final double temperature;
    private final int maxOutputTokens;
    private ModelConfigContext modelConfigContext;
    private ModelConfigService modelConfigService;
    private ModelClientFactory modelClientFactory;
    private ModelCircuitBreaker circuitBreaker;
    private Executor aiExecutor = java.util.concurrent.ForkJoinPool.commonPool();

    /** 注入用户级模型解析依赖；测试直接构造时不提供，回退到全局默认模型。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setModelConfigDependencies(ModelConfigContext context, ModelConfigService service,
                                           ModelClientFactory factory) {
        this.modelConfigContext = context;
        this.modelConfigService = service;
        this.modelClientFactory = factory;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setAiExecutor(@Qualifier("aiTaskExecutor") Executor aiExecutor) {
        this.aiExecutor = aiExecutor;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setCircuitBreaker(ModelCircuitBreaker circuitBreaker) {
        this.circuitBreaker = circuitBreaker;
    }

    public LocalChatClient(
            ObjectProvider<ChatClient> chatClientProvider,
            @Value("${spring.ai.openai.chat.base-url:${spring.ai.openai.base-url:unknown}}") String chatBaseUrl,
            @Value("${spring.ai.openai.chat.options.model:deepseek-ai/DeepSeek-V4-Flash}") String chatModel,
            @Value("${app.ai.fallback-models:Qwen/Qwen2.5-7B-Instruct}") String fallbackModels,
            @Value("${app.ai.retry.max-attempts:1}") int maxAttempts,
            @Value("${app.ai.retry.backoff-ms:500}") long retryBackoffMs,
            @Value("${app.ai.request-timeout-ms:60000}") long requestTimeoutMs,
            @Value("${app.ai.fallback-request-timeout-ms:90000}") long fallbackRequestTimeoutMs,
            @Value("${app.ai.sync-request-timeout-ms:20000}") long syncRequestTimeoutMs,
            @Value("${app.ai.fallback-strategy:local-answer}") String fallbackStrategy,
            @Value("${app.ai.temperature:0.0}") double temperature,
            @Value("${app.ai.max-output-tokens:1800}") int maxOutputTokens
    ) {
        this.chatClient = chatClientProvider.getIfAvailable();
        this.chatBaseUrl = chatBaseUrl;
        this.chatModel = chatModel;
        this.fallbackModels = parseModels(fallbackModels);
        this.maxAttempts = Math.max(1, maxAttempts);
        this.retryBackoff = Duration.ofMillis(Math.max(0, retryBackoffMs));
        this.requestTimeout = Duration.ofMillis(Math.max(1, requestTimeoutMs));
        this.fallbackRequestTimeout = Duration.ofMillis(Math.max(1, fallbackRequestTimeoutMs));
        this.syncRequestTimeout = Duration.ofMillis(Math.max(1, syncRequestTimeoutMs));
        this.fallbackStrategy = fallbackStrategy == null || fallbackStrategy.isBlank() ? "local-answer" : fallbackStrategy;
        this.temperature = Math.max(0.0, Math.min(1.0, temperature));
        this.maxOutputTokens = Math.max(256, maxOutputTokens);

        log.info(
                "AI client policy configured provider=openai-compatible primaryModel={} fallbackModels={} temperature={} maxOutputTokens={} primaryTimeoutMs={} fallbackTimeoutMs={} retryMaxAttempts={} retryBackoffMs={} fallbackStrategy={}",
                this.chatModel,
                this.fallbackModels,
                this.temperature,
                this.maxOutputTokens,
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
        String aiAnswer = sanitizeModelText(callSpringAi(prompt, history, options));

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
        String aiAnswer = sanitizeModelText(callSpringAi(prompt, history, options));

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
        return generate(prompt, List.of(), Map.of());
    }

    /**
     * 使用真实角色消息和当前用户指令生成文本。
     *
     * @param prompt 当前用户指令
     * @param history 最近对话历史
     * @param options 调用选项
     * @return 模型文本，模型不可用时返回 {@code null}
     */
    public String generate(String prompt, List<ChatMessage> history, Map<String, String> options) {
        return ModelOutputSanitizer.complete(sanitizeModelText(callSpringAi(prompt, history, options)));
    }

    public Flux<String> stream(String prompt, Map<String, String> options) {
        return stream(prompt, List.of(), options);
    }

    /**
     * 使用真实角色消息流式生成当前回答。
     *
     * @param prompt 当前用户指令
     * @param history 最近对话历史
     * @param options 调用选项
     * @return 模型文本流
     */
    public Flux<String> stream(String prompt, List<ChatMessage> history, Map<String, String> options) {
        ResolvedModel resolved = resolveModel();
        if (resolved.client() == null) {
            return Flux.error(new IllegalStateException("ChatClient is not available"));
        }

        String conversationId = options.getOrDefault("conversationId", "default");
        List<String> candidates = resolved.models().stream()
                .filter(model -> circuitBreaker == null || circuitBreaker.allowRequest(model))
                .collect(Collectors.toList());
        if (candidates.isEmpty()) {
            // 所有候选模型均被熔断,退化为直接尝试主模型,避免完全不可用。
            candidates = List.of(resolved.models().get(0));
            log.warn("All candidate models tripped circuit breaker, falling back to primary model={}", candidates.get(0));
        }

        AtomicBoolean receivedToken = new AtomicBoolean(false);
        Flux<String> chain = null;
        for (int index = 0; index < candidates.size(); index++) {
            final String model = candidates.get(index);
            final boolean primary = index == 0;
            StringBuilder stepBuffer = new StringBuilder();
            Flux<String> step = withModelErrorGuard(
                            ModelOutputSanitizer.stream(streamModel(prompt, history, conversationId,
                                    resolved.client(), model,
                                    primary ? resolved.requestTimeout() : resolved.fallbackRequestTimeout(),
                                    resolved.temperature(), resolved.maxOutputTokens()))
                                    .map(this::sanitizeModelText)
                                    .filter(token -> !token.isEmpty()),
                            stepBuffer)
                    .doOnNext(token -> receivedToken.set(true))
                    .doOnComplete(() -> { if (circuitBreaker != null) circuitBreaker.recordSuccess(model); })
                    .doOnError(error -> { if (circuitBreaker != null) circuitBreaker.recordFailure(model); });
            // 仅在尚未产出任何 token 时才切换到下一个候选模型,避免半截答案拼接。
            chain = chain == null ? step : chain.onErrorResume(error -> !receivedToken.get() ? step : Flux.error(error));
        }
        return chain != null ? chain : Flux.error(new IllegalStateException("No model available"));
    }

    /** 解析当前请求实际使用的模型，未启用动态配置时回退到全局默认。 */
    private ResolvedModel resolveModel() {
        if (modelConfigContext == null || modelConfigService == null || modelClientFactory == null) {
            log.info("resolveModel: using default chatClient (modelConfig dependencies not available)");
            return new ResolvedModel(chatClient, modelsToTry(), temperature, maxOutputTokens,
                    requestTimeout, fallbackRequestTimeout);
        }
        Long userId = modelConfigContext.get();
        ModelSpec spec = modelConfigService.resolve(userId, modelConfigContext.getSelectedModelId(), modelConfigContext.getPublicId());
        log.info("resolveModel: userId={} name={} model={} baseUrl={} apiKey={}***",
                userId, spec.name(), spec.model(), spec.baseUrl(),
                spec.apiKey() != null && spec.apiKey().length() > 4 ? spec.apiKey().substring(0, 4) : "null");
        ChatClient client = modelClientFactory.clientFor(spec);
        List<String> models = new ArrayList<>();
        models.add(spec.model());
        if (spec.fallbackModels() != null && !spec.fallbackModels().isBlank()) {
            Arrays.stream(spec.fallbackModels().split(","))
                    .map(String::trim)
                    .filter(model -> !model.isBlank() && !models.contains(model))
                    .forEach(models::add);
        }
        double resolvedTemperature = spec.temperature() == null ? temperature : Math.max(0.0, Math.min(1.0, spec.temperature()));
        int resolvedMaxTokens = spec.maxOutputTokens() == null ? maxOutputTokens : Math.max(256, spec.maxOutputTokens());
        Duration resolvedTimeout = spec.requestTimeoutMs() == null || spec.requestTimeoutMs() <= 0
                ? requestTimeout : Duration.ofMillis(spec.requestTimeoutMs());
        return new ResolvedModel(client, models, resolvedTemperature, resolvedMaxTokens, resolvedTimeout, fallbackRequestTimeout);
    }

    private record ResolvedModel(
            ChatClient client,
            List<String> models,
            double temperature,
            int maxOutputTokens,
            Duration requestTimeout,
            Duration fallbackRequestTimeout
    ) {
    }

    /**
     * 识别模型厂商把错误报文当正常内容返回的情况（HTTP 200 但响应体实为错误）。
     * 命中返回 {@link ModelProviderException}（含中文提示），否则返回 null。
     */
    private ModelProviderException detectModelError(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        if (MODEL_ERROR_RATE_LIMIT.matcher(text).find()) {
            return ModelProviderException.rateLimited(extractTraceId(text));
        }
        if (MODEL_ERROR_QUOTA.matcher(text).find()) {
            return ModelProviderException.quotaExceeded(extractTraceId(text));
        }
        if (MODEL_ERROR_BODY.matcher(text).find()) {
            return ModelProviderException.providerError(extractErrorCode(text), text, extractTraceId(text));
        }
        if (MODEL_ERROR_AUTH.matcher(text).find()) {
            return ModelProviderException.authError(extractTraceId(text));
        }
        return null;
    }

    private static String extractTraceId(String text) {
        if (text == null) return null;
        Matcher matcher = Pattern.compile("(?i)(trace[\\s_-]?id|trace_id)\\\"?\\s*:\\s*\\\"?([A-Za-z0-9]+)").matcher(text);
        return matcher.find() ? matcher.group(2) : null;
    }

    private static String extractErrorCode(String text) {
        if (text == null) return null;
        Matcher matcher = Pattern.compile("(?i)(code|error_code|errorCode)\\\"?\\s*:\\s*\\\"?([A-Za-z0-9_]+)").matcher(text);
        return matcher.find() ? matcher.group(2) : null;
    }

    /** 在流式 token 上累积检测厂商错误报文，命中即转为错误信号交由下游 resume/捕获。 */
    private Flux<String> withModelErrorGuard(Flux<String> flux, StringBuilder buffer) {
        return flux.doOnNext(token -> {
            buffer.append(token);
            ModelProviderException error = detectModelError(buffer.toString());
            if (error != null) {
                throw error;
            }
        });
    }

    String sanitizeModelText(String text) {
        if (text == null || text.indexOf('\uFFFD') < 0) {
            return text;
        }
        int replacements = (int) text.chars().filter(character -> character == '\uFFFD').count();
        log.warn("AI model response contained invalid Unicode replacement characters count={}", replacements);
        return text.replace("\uFFFD", "");
    }

    private Flux<String> streamModel(
            String prompt, List<ChatMessage> history, String conversationId, ChatClient client,
            String model, Duration timeout, double resolvedTemperature, int resolvedMaxTokens) {
        log.info("AI model stream started provider=openai-compatible model={} conversationId={} requestTimeoutMs={} promptLength={}",
                model, conversationId, timeout.toMillis(), prompt == null ? 0 : prompt.length());
        return client.prompt()
                .messages(toSpringAiMessages(history))
                .user(prompt)
                .options(chatOptions(model, resolvedTemperature, resolvedMaxTokens))
                .advisors(advisorSpec -> advisorSpec.param("conversationId", conversationId))
                .stream()
                .content()
                .timeout(timeout)
                .doOnComplete(() -> log.info(
                        "AI model stream completed model={} conversationId={}", model, conversationId))
                .doOnError(error -> {
                    log.warn("AI model stream failed model={} conversationId={} errorType={}", model, conversationId, error.getClass().getSimpleName());
                });
    }

    private String callSpringAi(String prompt, List<ChatMessage> history, Map<String, String> options) {
        ResolvedModel resolved = resolveModel();
        if (resolved.client() == null) {
            log.warn("AI model skipped reason=chatClient_not_available model={}", resolved.models().isEmpty() ? "unknown" : resolved.models().get(0));
            return null;
        }

        String conversationId = options.getOrDefault("conversationId", "default");
        List<String> models = resolved.models();
        log.info(
                "AI model call policy provider=openai-compatible primaryModel={} fallbackModels={} conversationId={} temperature={} primaryTimeoutMs={} fallbackTimeoutMs={} retryMaxAttempts={} retryBackoffMs={} fallbackStrategy={} promptLength={}",
                models.isEmpty() ? "unknown" : models.get(0),
                models.size() > 1 ? models.subList(1, models.size()) : List.of(),
                conversationId,
                resolved.temperature(),
                resolved.requestTimeout().toMillis(),
                resolved.fallbackRequestTimeout().toMillis(),
                maxAttempts,
                retryBackoff.toMillis(),
                fallbackStrategy,
                prompt == null ? 0 : prompt.length()
        );

        for (int modelIndex = 0; modelIndex < models.size(); modelIndex++) {
            String model = models.get(modelIndex);
            if (circuitBreaker != null && !circuitBreaker.allowRequest(model)) {
                log.warn("AI model skipped by circuit breaker model={} conversationId={}", model, conversationId);
                continue;
            }
            Duration timeout = modelIndex == 0 ? syncRequestTimeout : resolved.fallbackRequestTimeout();

            for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                long startedAt = System.currentTimeMillis();
                log.info(
                        "AI model call started provider=openai-compatible model={} modelRole={} conversationId={} attempt={} maxAttempts={} requestTimeoutMs={} promptLength={}",
                        model,
                        modelIndex == 0 ? "primary" : "fallback",
                        conversationId,
                        attempt,
                        maxAttempts,
                        timeout.toMillis(),
                        prompt == null ? 0 : prompt.length()
                );

                try {
                    ChatResponse response = callChatResponse(prompt, history, conversationId, resolved.client(),
                            model, timeout, resolved.temperature(), resolved.maxOutputTokens());
                    String content = content(response);
                    ModelProviderException modelError = detectModelError(content);
                    if (modelError != null) {
                        log.warn("AI model returned error payload model={} conversationId={} errorCode={}",
                                model, conversationId, modelError.getErrorCode());
                        throw modelError;
                    }
                    logModelResponse(response, model, conversationId, attempt, startedAt, content);
                    if (circuitBreaker != null) circuitBreaker.recordSuccess(model);
                    return content;
                } catch (RuntimeException exception) {
                    boolean retryable = logModelError(exception, model, conversationId, attempt, startedAt, modelIndex < models.size() - 1);
                    if (circuitBreaker != null) circuitBreaker.recordFailure(model);
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
                models.isEmpty() ? "unknown" : models.get(0),
                models.size() > 1 ? models.subList(1, models.size()) : List.of(),
                conversationId
        );
        return null;
    }

    private ChatResponse callChatResponse(
            String prompt, List<ChatMessage> history, String conversationId, ChatClient client,
            String model, Duration timeout, double resolvedTemperature, int resolvedMaxTokens) {
        CompletableFuture<ChatResponse> future = CompletableFuture.supplyAsync(() -> client.prompt()
                .messages(toSpringAiMessages(history))
                .user(prompt)
                .options(chatOptions(model, resolvedTemperature, resolvedMaxTokens))
                .advisors(advisorSpec -> advisorSpec.param(
                        "conversationId",
                        conversationId
                ))
                .call()
                .chatResponse(), aiExecutor);

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

    private OpenAiChatOptions chatOptions(String model, double resolvedTemperature, int resolvedMaxTokens) {
        return OpenAiChatOptions.builder()
                .model(model)
                .temperature(resolvedTemperature)
                .maxTokens(resolvedMaxTokens)
                .build();
    }

    List<Message> toSpringAiMessages(List<ChatMessage> history) {
        if (history == null || history.isEmpty()) {
            return List.of();
        }
        return history.stream()
                .filter(message -> message != null && message.content() != null && !message.content().isBlank())
                .map(message -> "assistant".equalsIgnoreCase(message.role())
                        ? new AssistantMessage(message.content())
                        : new UserMessage(message.content()))
                .map(Message.class::cast)
                .toList();
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
                "AI model call failed provider=openai-compatible model={} conversationId={} attempt={} maxAttempts={} durationMs={} errorType={} httpStatus={} exceptionClass={} rootCauseClass={} retryable={} willRetry={} willSwitchModel={} fallbackStrategy={} rootCauseMessage={}",
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
                fallbackStrategy,
                rootCause == null ? "null" : (rootCause.getMessage() != null ? rootCause.getMessage().substring(0, Math.min(200, rootCause.getMessage().length())) : "null")
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

    private static class AiModelCallException extends RuntimeException {

        AiModelCallException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
