package com.example.workbench.modelconfig;

import com.example.workbench.auth.AppUser;
import com.example.workbench.auth.AdminAuthorizationService;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AiModelService {

    private final AiModelRepository aiModelRepository;
    private final AdminAuthorizationService adminAuthorizationService;
    private final ModelClientFactory modelClientFactory;

    public AiModelService(AiModelRepository aiModelRepository,
                          AdminAuthorizationService adminAuthorizationService,
                          ModelClientFactory modelClientFactory) {
        this.aiModelRepository = aiModelRepository;
        this.adminAuthorizationService = adminAuthorizationService;
        this.modelClientFactory = modelClientFactory;
    }

    @Transactional(readOnly = true)
    public List<AiModelResponse> list() {
        return aiModelRepository.findAllByOrderByIdAsc().stream().map(AiModelResponse::from).toList();
    }

    @Transactional
    public AiModelResponse create(AppUser actor, AiModelRequest request) {
        adminAuthorizationService.requireAdmin(actor);
        AiModel entity = new AiModel(request.name().strip(), request.baseUrl().strip(),
                request.apiKey().strip(), request.model().strip());
        entity.update(request.name().strip(), request.baseUrl().strip(), request.apiKey().strip(),
                request.model().strip(), request.temperature(), request.topP(),
                request.maxOutputTokens(), request.requestTimeoutMs(), normalizeFallback(request.fallbackModels()));
        entity.markDefault(request.isDefault());
        entity.setEnabled(request.enabled());
        entity = aiModelRepository.save(entity);
        ensureSingleDefault(entity.getId());
        return AiModelResponse.from(entity);
    }

    @Transactional
    public AiModelResponse update(AppUser actor, Long id, AiModelRequest request) {
        adminAuthorizationService.requireAdmin(actor);
        AiModel entity = require(id);
        String oldBaseUrl = entity.getBaseUrl();
        String oldApiKey = entity.getApiKey();
        entity.update(request.name().strip(), request.baseUrl().strip(), request.apiKey().strip(),
                request.model().strip(), request.temperature(), request.topP(),
                request.maxOutputTokens(), request.requestTimeoutMs(), normalizeFallback(request.fallbackModels()));
        entity.markDefault(request.isDefault());
        entity.setEnabled(request.enabled());
        entity = aiModelRepository.save(entity);
        ensureSingleDefault(entity.getId());
        if (!oldBaseUrl.equals(entity.getBaseUrl()) || !oldApiKey.equals(entity.getApiKey())) {
            modelClientFactory.invalidate(oldBaseUrl, oldApiKey);
        }
        return AiModelResponse.from(entity);
    }

    @Transactional
    public void delete(AppUser actor, Long id) {
        adminAuthorizationService.requireAdmin(actor);
        AiModel entity = require(id);
        aiModelRepository.delete(entity);
        modelClientFactory.invalidate(entity.getBaseUrl(), entity.getApiKey());
    }

    @Transactional
    public AiModelResponse setDefault(AppUser actor, Long id) {
        adminAuthorizationService.requireAdmin(actor);
        AiModel entity = require(id);
        aiModelRepository.findAll().forEach(model -> {
            if (model.isDefault() && !model.getId().equals(id)) {
                model.markDefault(false);
                aiModelRepository.save(model);
            }
        });
        entity.markDefault(true);
        return AiModelResponse.from(aiModelRepository.save(entity));
    }

    private void ensureSingleDefault(Long currentId) {
        if (aiModelRepository.findFirstByIsDefaultTrue().isEmpty()) {
            return;
        }
        aiModelRepository.findAll().forEach(model -> {
            if (model.isDefault() && !model.getId().equals(currentId)) {
                model.markDefault(false);
                aiModelRepository.save(model);
            }
        });
    }

    private AiModel require(Long id) {
        return aiModelRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "模型不存在"));
    }

    private String normalizeFallback(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
