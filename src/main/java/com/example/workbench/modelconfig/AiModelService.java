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
    public List<AiModelResponse> list() {
        return aiModelRepository.findAllByOrderByIdAsc().stream().map(AiModelResponse::from).toList();
    }

    @Transactional
    public AiModelResponse create(AppUser actor, AiModelRequest request) {
        adminAuthorizationService.requireAdmin(actor);
        AiModelType type = request.modelType() != null ? request.modelType() : AiModelType.CHAT;
        AiModel entity = new AiModel(request.name().strip(), request.baseUrl().strip(),
                request.apiKey().strip(), request.model().strip());
        entity.update(request.name().strip(), request.baseUrl().strip(), request.apiKey().strip(),
                request.model().strip(), type, request.temperature(), request.topP(),
                request.maxOutputTokens(), request.requestTimeoutMs(), normalizeFallback(request.fallbackModels()));
        entity.markDefault(request.isDefault());
        entity.setEnabled(request.enabled());
        entity = aiModelRepository.save(entity);
        ensureSingleDefault(entity);
        return AiModelResponse.from(entity);
    }

    @Transactional
    public AiModelResponse update(AppUser actor, Long id, AiModelRequest request) {
        adminAuthorizationService.requireAdmin(actor);
        AiModel entity = require(id);
        AiModelType type = request.modelType() != null ? request.modelType() : entity.getModelType();
        String oldBaseUrl = entity.getBaseUrl();
        String oldApiKey = entity.getApiKey();
        entity.update(request.name().strip(), request.baseUrl().strip(), request.apiKey().strip(),
                request.model().strip(), type, request.temperature(), request.topP(),
                request.maxOutputTokens(), request.requestTimeoutMs(), normalizeFallback(request.fallbackModels()));
        entity.markDefault(request.isDefault());
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
        adminAuthorizationService.requireAdmin(actor);
        AiModel entity = require(id);
        aiModelRepository.delete(entity);
        modelClientFactory.invalidate(entity.getBaseUrl(), entity.getApiKey());
    }

    @Transactional
    public AiModelResponse setDefault(AppUser actor, Long id) {
        adminAuthorizationService.requireAdmin(actor);
        AiModel entity = require(id);
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

    private AiModel require(Long id) {
        return aiModelRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "模型不存在"));
    }

    /** 测试模型连通性：调用 GET {baseUrl}/models 验证地址和密钥是否有效 */
    public void testConnect(AppUser actor, Long id) {
        adminAuthorizationService.requireAdmin(actor);
        AiModel entity = require(id);
        String url = entity.getBaseUrl().replaceAll("/+$", "") + "/models";
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .build();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + entity.getApiKey())
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
