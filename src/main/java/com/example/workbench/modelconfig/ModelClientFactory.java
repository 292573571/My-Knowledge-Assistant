package com.example.workbench.modelconfig;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

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
        String key = baseUrl + "\u0000" + spec.apiKey() + "\u0000" + spec.requestTimeoutMs();
        return cache.computeIfAbsent(key, k -> build(spec));
    }

    public void invalidate(String baseUrl, String apiKey) {
        String prefix = baseUrl.replaceAll("/+$", "") + "\u0000" + apiKey + "\u0000";
        cache.keySet().removeIf(k -> k.startsWith(prefix));
        log.info("Model client cache invalidated baseUrl={}", baseUrl);
    }

    private ChatClient build(ModelSpec spec) {
        String baseUrl = spec.baseUrl().replaceAll("/+$", "");
        String completionsPath = "/v1/chat/completions";
        String embeddingsPath = "/v1/embeddings";
        if (baseUrl.endsWith("/v1")) {
            completionsPath = "/chat/completions";
            embeddingsPath = "/embeddings";
        }
        long readTimeoutMs = spec.requestTimeoutMs() != null && spec.requestTimeoutMs() > 0
                ? Math.min(Math.max(spec.requestTimeoutMs(), 5_000L), 300_000L)
                : defaultReadTimeoutMs;
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofMillis(connectTimeoutMs));
        requestFactory.setReadTimeout(Duration.ofMillis(readTimeoutMs));
        RestClient.Builder restClientBuilder = RestClient.builder().requestFactory(requestFactory);
        OpenAiApi openAiApi = OpenAiApi.builder()
                .baseUrl(baseUrl)
                .apiKey(spec.apiKey())
                .completionsPath(completionsPath)
                .embeddingsPath(embeddingsPath)
                .restClientBuilder(restClientBuilder)
                .build();
        OpenAiChatModel chatModel = OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .build();
        log.info("Model client built model={} baseUrl={} completionsPath={} readTimeoutMs={}",
                spec.model(), baseUrl, completionsPath, readTimeoutMs);
        return ChatClient.builder(chatModel).build();
    }
}
