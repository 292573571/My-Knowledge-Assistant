package com.example.workbench.modelconfig;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 按模型规格动态构建并缓存 ChatClient；不同 base_url/api_key 对应独立客户端。 */
@Component
public class ModelClientFactory {

    private static final Logger log = LoggerFactory.getLogger(ModelClientFactory.class);

    private final Map<String, ChatClient> cache = new ConcurrentHashMap<>();
    private final int connectTimeoutMs;
    private final long defaultReadTimeoutMs;

    public ModelClientFactory(
            @Value("${app.ai.connect-timeout-ms:10000}") int connectTimeoutMs,
            @Value("${app.ai.request-timeout-ms:60000}") long defaultReadTimeoutMs) {
        this.connectTimeoutMs = connectTimeoutMs;
        this.defaultReadTimeoutMs = defaultReadTimeoutMs;
    }

    public ChatClient clientFor(ModelSpec spec) {
        String baseUrl = spec.baseUrl().replaceAll("/+$", "");
        String key = baseUrl + "\u0000" + spec.apiKey() + "\u0000" + spec.requestTimeoutMs()
                + "\u0000" + spec.model() + "\u0000" + spec.temperature() + "\u0000"
                + spec.topP() + "\u0000" + spec.maxOutputTokens();
        return cache.computeIfAbsent(key, k -> build(spec));
    }

    public void invalidate(String baseUrl, String apiKey) {
        String prefix = baseUrl.replaceAll("/+$", "") + "\u0000" + apiKey + "\u0000";
        cache.keySet().removeIf(k -> k.startsWith(prefix));
        log.info("Model client cache invalidated baseUrl={}", baseUrl);
    }

    private ChatClient build(ModelSpec spec) {
        String baseUrl = spec.baseUrl().replaceAll("/+$", "");
        // Spring AI 2.0 移除 completionsPath 自定义，官方 SDK 在 baseUrl 后拼 /chat/completions；
        // 历史配置 baseUrl 不带 /v1（如 https://api.siliconflow.cn）会打到 /chat/completions → 404。
        if (!baseUrl.endsWith("/v1")) {
            baseUrl = baseUrl + "/v1";
        }
        long readTimeoutMs = spec.requestTimeoutMs() != null && spec.requestTimeoutMs() > 0
                ? Math.min(Math.max(spec.requestTimeoutMs(), 5_000L), 300_000L)
                : defaultReadTimeoutMs;
        OpenAiChatOptions.Builder optionsBuilder = OpenAiChatOptions.builder()
                .model(spec.model())
                .baseUrl(baseUrl)
                .apiKey(spec.apiKey())
                .timeout(Duration.ofMillis(readTimeoutMs));
        if (spec.temperature() != null) {
            optionsBuilder.temperature(spec.temperature());
        }
        if (spec.topP() != null) {
            optionsBuilder.topP(spec.topP());
        }
        if (spec.maxOutputTokens() != null) {
            optionsBuilder.maxTokens(spec.maxOutputTokens());
        }
        // 流式调用默认不返回 usage；打开后 provider 会在最后一个 chunk 附带 token 统计，
        // 教学 Agent 的用量才可计量（否则流式讲解只能记 0）。
        optionsBuilder.streamUsage(true);
        OpenAiChatModel chatModel = OpenAiChatModel.builder()
                .options(optionsBuilder.build())
                .build();
        log.info("Model client built model={} baseUrl={} readTimeoutMs={} connectTimeoutMs={}",
                spec.model(), baseUrl, readTimeoutMs, connectTimeoutMs);
        return ChatClient.builder(chatModel).build();
    }
}
