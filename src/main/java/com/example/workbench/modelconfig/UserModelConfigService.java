package com.example.workbench.modelconfig;

import com.example.workbench.auth.AppUser;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class UserModelConfigService {

    private final UserModelConfigRepository userModelConfigRepository;
    private final AiModelRepository aiModelRepository;
    private final ModelConfigService modelConfigService;

    public UserModelConfigService(UserModelConfigRepository userModelConfigRepository,
                                  AiModelRepository aiModelRepository,
                                  ModelConfigService modelConfigService) {
        this.userModelConfigRepository = userModelConfigRepository;
        this.aiModelRepository = aiModelRepository;
        this.modelConfigService = modelConfigService;
    }

    @Transactional(readOnly = true)
    public UserModelConfigResponse get(AppUser user) {
        UserModelConfig config = userModelConfigRepository.findByUserId(user.getId()).orElse(null);
        List<AiModelResponse> pool = aiModelRepository.findAllByOrderByIdAsc().stream()
                .filter(AiModel::isEnabled)
                .map(AiModelResponse::from)
                .toList();
        Long defaultModelId = aiModelRepository.findFirstByIsDefaultTrueAndModelTypeAndEnabledTrue(AiModelType.CHAT)
                .map(AiModel::getId).orElse(null);
        Long defaultEmbeddingId = aiModelRepository.findFirstByIsDefaultTrueAndModelTypeAndEnabledTrue(AiModelType.EMBEDDING)
                .map(AiModel::getId).orElse(null);
        ModelSpec resolved = modelConfigService.resolve(user.getId());
        return new UserModelConfigResponse(
                config == null ? UserModelMode.FOLLOW_DEFAULT : config.getMode(),
                config == null ? null : config.getModelId(),
                config == null || config.getMode() != UserModelMode.CUSTOM ? null : toSpec(config),
                resolved,
                pool,
                defaultModelId,
                defaultEmbeddingId
        );
    }

    @Transactional
    public UserModelConfigResponse save(AppUser user, UserModelConfigRequest request) {
        UserModelConfig config = userModelConfigRepository.findByUserId(user.getId())
                .orElseGet(() -> new UserModelConfig(user.getId()));
        switch (request.mode()) {
            case FOLLOW_DEFAULT -> config.followDefault();
            case USE_POOL_MODEL -> {
                if (request.modelId() == null) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "选择模型时必须指定模型");
                }
                aiModelRepository.findById(request.modelId()).filter(AiModel::isEnabled)
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "所选模型不存在或已禁用"));
                config.usePoolModel(request.modelId());
            }
            case CUSTOM -> {
                if (request.baseUrl() == null || request.baseUrl().isBlank()
                        || request.apiKey() == null || request.apiKey().isBlank()
                        || request.model() == null || request.model().isBlank()) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "自定义模型必须填写 API 地址、API Key 和模型标识");
                }
                config.useCustom(request.name() == null || request.name().isBlank() ? "自定义模型" : request.name().strip(),
                        request.baseUrl().strip(), request.apiKey().strip(), request.model().strip(),
                        request.temperature(), request.topP(), request.maxOutputTokens(),
                        request.requestTimeoutMs(), normalizeFallback(request.fallbackModels()));
            }
        }
        userModelConfigRepository.save(config);
        return get(user);
    }

    private ModelSpec toSpec(UserModelConfig config) {
        return new ModelSpec(config.getName(), config.getBaseUrl(), config.getApiKey(), config.getModel(),
                config.getTemperature(), config.getTopP(), config.getMaxOutputTokens(),
                config.getRequestTimeoutMs(), config.getFallbackModels());
    }

    private String normalizeFallback(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
