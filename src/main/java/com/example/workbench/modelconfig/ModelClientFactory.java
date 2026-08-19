package com.example.workbench.modelconfig;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.stereotype.Component;

/** 按模型规格动态构建并缓存 ChatClient；不同 base_url/api_key 对应独立客户端。 */
@Component
public class ModelClientFactory {

    private static final Logger log = LoggerFactory.getLogger(ModelClientFactory.class);

    private final Map<String, ChatClient> cache = new ConcurrentHashMap<>();

    public ChatClient clientFor(ModelSpec spec) {
        String baseUrl = spec.baseUrl().replaceAll("/+$", "");
        String key = baseUrl + "\u0000" + spec.apiKey();
        return cache.computeIfAbsent(key, k -> build(spec));
    }

    public void invalidate(String baseUrl, String apiKey) {
        String key = baseUrl.replaceAll("/+$", "") + "\u0000" + apiKey;
        cache.remove(key);
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
        OpenAiApi openAiApi = OpenAiApi.builder()
                .baseUrl(baseUrl)
                .apiKey(spec.apiKey())
                .completionsPath(completionsPath)
                .embeddingsPath(embeddingsPath)
                .build();
        OpenAiChatModel chatModel = OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .build();
        log.info("Model client built model={} baseUrl={} completionsPath={}", spec.model(), baseUrl, completionsPath);
        return ChatClient.builder(chatModel).build();
    }
}
