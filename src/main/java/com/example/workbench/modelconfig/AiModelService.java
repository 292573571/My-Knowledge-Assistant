package com.example.workbench.modelconfig;

import com.example.workbench.auth.AppUser;
import com.example.workbench.auth.AdminAuthorizationService;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
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
    public List<AiModelResponse> list(AppUser actor) {
        return aiModelRepository.findAllByOrderByIdAsc().stream()
                .filter(model -> model.getOwnerPublicId() == null
                        || model.getOwnerPublicId().equals(actor.getPublicId()))
                .map(AiModelResponse::from)
                .toList();
    }

    @Transactional
    public AiModelResponse create(AppUser actor, AiModelRequest request) {
        adminAuthorizationService.requireSuperAdmin(actor);
        AiModelType type = request.modelType() != null ? request.modelType() : AiModelType.CHAT;
        AiModel entity = new AiModel(request.name().strip(), request.baseUrl().strip(),
                request.apiKey().strip(), request.model().strip());
        entity.update(request.name().strip(), request.baseUrl().strip(), request.apiKey().strip(),
                request.model().strip(), type, request.temperature(), request.topP(),
                request.maxOutputTokens(), request.requestTimeoutMs(), normalizeFallback(request.fallbackModels()));
        if (request.isDefault() && !request.enabled()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "停用模型不能设置为默认模型");
        }
        entity.markDefault(request.isDefault());
        entity.setEnabled(request.enabled());
        entity = aiModelRepository.save(entity);
        ensureSingleDefault(entity);
        return AiModelResponse.from(entity);
    }

    @Transactional(readOnly = true)
    public List<AiModelResponse> listPersonal(AppUser actor) {
        return aiModelRepository.findByOwnerPublicId(actor.getPublicId()).stream()
                .filter(model -> model.getModelType() == AiModelType.CHAT)
                .sorted(java.util.Comparator.comparing(AiModel::getId))
                .map(AiModelResponse::from)
                .toList();
    }

    @Transactional
    public AiModelResponse createPersonal(AppUser actor, AiModelRequest request) {
        AiModel entity = new AiModel(request.name().strip(), request.baseUrl().strip(),
                request.apiKey().strip(), request.model().strip());
        entity.update(request.name().strip(), request.baseUrl().strip(), request.apiKey().strip(),
                request.model().strip(), AiModelType.CHAT, request.temperature(), request.topP(),
                request.maxOutputTokens(), request.requestTimeoutMs(), normalizeFallback(request.fallbackModels()));
        entity.setEnabled(true);
        entity.markDefault(false);
        entity.setOwnerPublicId(actor.getPublicId());
        return AiModelResponse.from(aiModelRepository.save(entity));
    }

    @Transactional
    public AiModelResponse updatePersonal(AppUser actor, Long id, AiModelRequest request) {
        AiModel entity = require(id);
        if (!actor.getPublicId().equals(entity.getOwnerPublicId()) || entity.getModelType() != AiModelType.CHAT) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "只能修改自己配置的对话模型");
        }
        String oldBaseUrl = entity.getBaseUrl();
        String oldApiKey = entity.getApiKey();
        entity.update(request.name().strip(), request.baseUrl().strip(), request.apiKey().strip(),
                request.model().strip(), AiModelType.CHAT, request.temperature(), request.topP(),
                request.maxOutputTokens(), request.requestTimeoutMs(), normalizeFallback(request.fallbackModels()));
        entity.setEnabled(true);
        entity.markDefault(false);
        entity = aiModelRepository.save(entity);
        if (!oldBaseUrl.equals(entity.getBaseUrl()) || !oldApiKey.equals(entity.getApiKey())) {
            modelClientFactory.invalidate(oldBaseUrl, oldApiKey);
        }
        return AiModelResponse.from(entity);
    }

    @Transactional
    public void deletePersonal(AppUser actor, Long id) {
        AiModel entity = require(id);
        if (!actor.getPublicId().equals(entity.getOwnerPublicId()) || entity.getModelType() != AiModelType.CHAT) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "只能删除自己配置的对话模型");
        }
        aiModelRepository.delete(entity);
        modelClientFactory.invalidate(entity.getBaseUrl(), entity.getApiKey());
    }

    @Transactional
    public AiModelResponse update(AppUser actor, Long id, AiModelRequest request) {
        AiModel entity = require(id);
        AiModelType requestedType = request.modelType() != null ? request.modelType() : entity.getModelType();
        authorizeMutation(actor, entity, requestedType);
        String oldBaseUrl = entity.getBaseUrl();
        String oldApiKey = entity.getApiKey();
        entity.update(request.name().strip(), request.baseUrl().strip(), request.apiKey().strip(),
                request.model().strip(), requestedType, request.temperature(), request.topP(),
                request.maxOutputTokens(), request.requestTimeoutMs(), normalizeFallback(request.fallbackModels()));
        boolean canSetDefault = adminAuthorizationService.isSuperAdmin(actor);
        if (request.isDefault() && !canSetDefault) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "只有超级管理员可以设置默认模型");
        }
        if (request.isDefault() && !request.enabled()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "停用模型不能设置为默认模型");
        }
        if (canSetDefault) {
            entity.markDefault(request.enabled() && request.isDefault());
        } else if (!request.enabled()) {
            entity.markDefault(false);
        }
        entity.setEnabled(request.enabled());
        entity = aiModelRepository.save(entity);
        ensureSingleDefault(entity);
        if (!oldBaseUrl.equals(entity.getBaseUrl()) || !oldApiKey.equals(entity.getApiKey())) {
            modelClientFactory.invalidate(oldBaseUrl, oldApiKey);
        }
        return AiModelResponse.from(entity);
    }

    @Transactional
    public void delete(AppUser actor, Long id) {
        AiModel entity = require(id);
        authorizeMutation(actor, entity, entity.getModelType());
        aiModelRepository.delete(entity);
        modelClientFactory.invalidate(entity.getBaseUrl(), entity.getApiKey());
    }

    @Transactional
    public AiModelResponse setDefault(AppUser actor, Long id) {
        adminAuthorizationService.requireSuperAdmin(actor);
        AiModel entity = require(id);
        if (!entity.isEnabled()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "停用模型不能设置为默认模型");
        }
        aiModelRepository.findByModelType(entity.getModelType()).forEach(model -> {
            if (model.isDefault() && !model.getId().equals(id)) {
                model.markDefault(false);
                aiModelRepository.save(model);
            }
        });
        entity.markDefault(true);
        return AiModelResponse.from(aiModelRepository.save(entity));
    }

    private void ensureSingleDefault(AiModel saved) {
        AiModelType type = saved.getModelType();
        if (saved.isDefault()) {
            aiModelRepository.findByModelType(type).forEach(model -> {
                if (model.isDefault() && !model.getId().equals(saved.getId())) {
                    model.markDefault(false);
                    aiModelRepository.save(model);
                }
            });
        }
    }

    private void authorizeMutation(AppUser actor, AiModel entity, AiModelType requestedType) {
        if (requestedType == AiModelType.EMBEDDING) {
            adminAuthorizationService.requireSuperAdmin(actor);
            return;
        }
        String owner = entity.getOwnerPublicId();
        if (owner == null && adminAuthorizationService.isSuperAdmin(actor)) {
            return;
        }
        if (owner == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "系统模型仅超级管理员可修改");
        }
        if (!owner.equals(actor.getPublicId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "只能修改自己添加的模型");
        }
    }

    private AiModel require(Long id) {
        return aiModelRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "模型不存在"));
    }

    /** 测试模型连通性：调用 GET {baseUrl}/models 验证地址和密钥是否有效 */
    public void testConnect(AppUser actor, Long id) {
        AiModel entity = require(id);
        authorizeMutation(actor, entity, entity.getModelType());
        testConnection(entity.getBaseUrl(), entity.getApiKey());
    }

    public void testPersonalConfig(AppUser actor, AiModelRequest request) {
        if (request.modelType() != null && request.modelType() != AiModelType.CHAT) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "个人模型仅支持对话模型");
        }
        testConnection(request.baseUrl().strip(), request.apiKey().strip());
    }

    private void testConnection(String baseUrl, String apiKey) {
        String url = baseUrl.replaceAll("/+$", "") + "/models";
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .build();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + apiKey)
                    .GET()
                    .timeout(Duration.ofSeconds(15))
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "连接测试失败，HTTP " + response.statusCode() + ": " + response.body());
            }
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "连接测试失败: " + e.getMessage());
        }
    }

    private String normalizeFallback(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
