package com.example.workbench.modelconfig;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** 解析用户实际使用的模型规格，并管理全局模型池和用户配置。 */
@Service
public class ModelConfigService {

    private static final Logger log = LoggerFactory.getLogger(ModelConfigService.class);

    private final AiModelRepository aiModelRepository;
    private final UserModelConfigRepository userModelConfigRepository;

    private final String defaultBaseUrl;
    private final String defaultApiKey;
    private final String defaultModel;
    private final double defaultTemperature;
    private final int defaultMaxOutputTokens;
    private final long defaultRequestTimeoutMs;
    private final String defaultFallbackModels;

    public ModelConfigService(
            AiModelRepository aiModelRepository,
            UserModelConfigRepository userModelConfigRepository,
            @Value("${spring.ai.openai.chat.base-url:${spring.ai.openai.base-url:}}") String defaultBaseUrl,
            @Value("${spring.ai.openai.chat.api-key:${spring.ai.openai.api-key:}}") String defaultApiKey,
            @Value("${spring.ai.openai.chat.options.model:deepseek-ai/DeepSeek-V4-Flash}") String defaultModel,
            @Value("${app.ai.temperature:0.0}") double defaultTemperature,
            @Value("${app.ai.max-output-tokens:1800}") int defaultMaxOutputTokens,
            @Value("${app.ai.request-timeout-ms:20000}") long defaultRequestTimeoutMs,
            @Value("${app.ai.fallback-models:Qwen/Qwen2.5-7B-Instruct}") String defaultFallbackModels
    ) {
        this.aiModelRepository = aiModelRepository;
        this.userModelConfigRepository = userModelConfigRepository;
        this.defaultBaseUrl = defaultBaseUrl;
        this.defaultApiKey = defaultApiKey;
        this.defaultModel = defaultModel;
        this.defaultTemperature = defaultTemperature;
        this.defaultMaxOutputTokens = defaultMaxOutputTokens;
        this.defaultRequestTimeoutMs = defaultRequestTimeoutMs;
        this.defaultFallbackModels = defaultFallbackModels;
    }

    /** 解析用户对话模型的完整配置 */
    public ModelSpec resolve(Long userId) {
        if (userId == null) {
            log.info("resolve: userId is null, using defaultChatSpec");
            return defaultChatSpec();
        }
        UserModelConfig config = userModelConfigRepository.findByUserId(userId).orElse(null);
        if (config == null || config.getMode() == UserModelMode.FOLLOW_DEFAULT) {
            log.info("resolve: userId={} mode=FOLLOW_DEFAULT, using defaultChatSpec", userId);
            return defaultChatSpec();
        }
        if (config.getMode() == UserModelMode.USE_POOL_MODEL) {
            AiModel model = config.getModelId() == null ? null
                    : aiModelRepository.findById(config.getModelId()).filter(AiModel::isEnabled).orElse(null);
            if (model != null) {
                log.info("resolve: userId={} mode=USE_POOL_MODEL modelId={} name={}", userId, model.getId(), model.getName());
                return toSpec(model);
            }
            log.warn("User selected model unavailable or disabled userId={} modelId={}", userId, config.getModelId());
            return defaultChatSpec();
        }
        log.info("resolve: userId={} mode=CUSTOM name={} model={}", userId, config.getName(), config.getModel());
        return toSpec(config);
    }

    /** 返回模型池中启用的默认对话模型，无则回退配置默认值 */
    public ModelSpec defaultChatSpec() {
        AiModel adminDefault = aiModelRepository.findFirstByIsDefaultTrueAndModelTypeAndEnabledTrue(AiModelType.CHAT)
                .orElse(null);
        if (adminDefault != null) {
            log.info("defaultChatSpec: using pool default id={} name={} model={} baseUrl={}", adminDefault.getId(), adminDefault.getName(), adminDefault.getModel(), adminDefault.getBaseUrl());
            return toSpec(adminDefault);
        }
        log.info("defaultChatSpec: no pool default CHAT model, falling back to application.properties baseUrl={} model={}", defaultBaseUrl, defaultModel);
        return new ModelSpec("默认对话模型", defaultBaseUrl, defaultApiKey, defaultModel,
                defaultTemperature, null, defaultMaxOutputTokens, defaultRequestTimeoutMs, defaultFallbackModels);
    }

    /** 返回模型池中启用的默认嵌入模型，无则回退 application.properties 的嵌入配置 */
    public ModelSpec defaultEmbeddingSpec() {
        AiModel adminDefault = aiModelRepository.findFirstByIsDefaultTrueAndModelTypeAndEnabledTrue(AiModelType.EMBEDDING)
                .orElse(null);
        if (adminDefault != null) {
            return toSpec(adminDefault);
        }
        return null;
    }

    private ModelSpec toSpec(AiModel model) {
        return new ModelSpec(model.getName(), model.getBaseUrl(), model.getApiKey(), model.getModel(),
                model.getTemperature(), model.getTopP(), model.getMaxOutputTokens(),
                model.getRequestTimeoutMs(), model.getFallbackModels());
    }

    private ModelSpec toSpec(UserModelConfig config) {
        return new ModelSpec(config.getName(), config.getBaseUrl(), config.getApiKey(), config.getModel(),
                config.getTemperature(), config.getTopP(), config.getMaxOutputTokens(),
                config.getRequestTimeoutMs(), config.getFallbackModels());
    }
}
