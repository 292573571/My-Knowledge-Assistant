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

    public ModelSpec resolve(Long userId) {
        if (userId == null) {
            return defaultSpec();
        }
        UserModelConfig config = userModelConfigRepository.findByUserId(userId).orElse(null);
        if (config == null || config.getMode() == UserModelMode.FOLLOW_DEFAULT) {
            return defaultSpec();
        }
        if (config.getMode() == UserModelMode.USE_POOL_MODEL) {
            AiModel model = config.getModelId() == null ? null
                    : aiModelRepository.findById(config.getModelId()).filter(AiModel::isEnabled).orElse(null);
            if (model != null) {
                return toSpec(model);
            }
            log.warn("User selected model unavailable or disabled userId={} modelId={}", userId, config.getModelId());
            return defaultSpec();
        }
        return toSpec(config);
    }

    private ModelSpec defaultSpec() {
        AiModel adminDefault = aiModelRepository.findFirstByIsDefaultTrueAndEnabledTrue().orElse(null);
        if (adminDefault != null) {
            return toSpec(adminDefault);
        }
        return new ModelSpec("默认模型", defaultBaseUrl, defaultApiKey, defaultModel,
                defaultTemperature, null, defaultMaxOutputTokens, defaultRequestTimeoutMs, defaultFallbackModels);
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
