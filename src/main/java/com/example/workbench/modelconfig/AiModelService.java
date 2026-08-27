package com.example.workbench.modelconfig;

import com.example.workbench.auth.AppUser;
import com.example.workbench.auth.AdminAuthorizationService;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AiModelService {

    private static final Logger log = LoggerFactory.getLogger(AiModelService.class);

    private final AiModelRepository aiModelRepository;
    private final AdminAuthorizationService adminAuthorizationService;
    private final ModelClientFactory modelClientFactory;
    private final boolean requireHttps;
    private final boolean allowLocalhost;
    private final Set<String> allowedHosts;

    public AiModelService(AiModelRepository aiModelRepository,
                          AdminAuthorizationService adminAuthorizationService,
                          ModelClientFactory modelClientFactory) {
        this(aiModelRepository, adminAuthorizationService, modelClientFactory, "development", "", false, "");
    }

    @Autowired
    public AiModelService(AiModelRepository aiModelRepository,
                          AdminAuthorizationService adminAuthorizationService,
                          ModelClientFactory modelClientFactory,
                          @Value("${app.logging.environment:development}") String environment,
                          @Value("${app.ai.model-test.require-https:}") String requireHttps,
                          @Value("${app.ai.model-test.allow-localhost:false}") boolean allowLocalhost,
                          @Value("${app.ai.model-test.allowed-hosts:}") String allowedHosts) {
        this.aiModelRepository = aiModelRepository;
        this.adminAuthorizationService = adminAuthorizationService;
        this.modelClientFactory = modelClientFactory;
        this.requireHttps = requireHttps == null || requireHttps.isBlank()
                ? "production".equalsIgnoreCase(environment) || "prod".equalsIgnoreCase(environment)
                : Boolean.parseBoolean(requireHttps);
        this.allowLocalhost = allowLocalhost;
        this.allowedHosts = parseAllowedHosts(allowedHosts);
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
            URI currentUri = URI.create(url);
            validateEndpoint(currentUri, allowLocalhost);
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build();
            for (int redirects = 0; redirects <= 5; redirects++) {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(currentUri)
                        .header("Authorization", "Bearer " + apiKey)
                        .GET()
                        .timeout(Duration.ofSeconds(15))
                        .build();
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() >= 300 && response.statusCode() < 400) {
                    if (redirects == 5 || response.headers().firstValue("Location").isEmpty()) {
                        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "连接测试失败，重定向次数过多或缺少目标地址");
                    }
                    currentUri = currentUri.resolve(response.headers().firstValue("Location").get());
                    validateEndpoint(currentUri, false);
                    continue;
                }
                if (response.statusCode() != 200) {
                    String body = response.body();
                    log.debug("模型连通测试失败 url={} status={} body={}", currentUri, response.statusCode(), body);
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "连接测试失败，HTTP " + response.statusCode() + ": " + truncateBody(body));
                }
                return;
            }
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "连接测试失败: " + e.getMessage());
        }
    }

    private void validateEndpoint(URI uri, boolean allowLocalhostEndpoint) {
        if (uri == null || uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null
                || (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme()))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "连接测试失败: API 地址无效");
        }
        if (requireHttps && !"https".equalsIgnoreCase(uri.getScheme())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "连接测试失败: 生产环境仅允许 HTTPS 地址");
        }

        String host = uri.getHost().toLowerCase(Locale.ROOT);
        boolean localHost = isLocalhost(host);
        if (localHost && !allowLocalhostEndpoint) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "连接测试失败: localhost 仅允许在显式配置后使用");
        }
        boolean allowedHost = allowedHosts.contains(host) || (localHost && allowLocalhostEndpoint);
        try {
            InetAddress[] addresses = InetAddress.getAllByName(host);
            if (addresses.length == 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "连接测试失败: API 地址无法解析");
            }
            if (!allowedHost && Arrays.stream(addresses).anyMatch(this::isRestrictedAddress)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "连接测试失败: API 地址指向受限网络地址");
            }
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "连接测试失败: API 地址无法解析");
        }
    }

    private boolean isLocalhost(String host) {
        return "localhost".equals(host) || "localhost.localdomain".equals(host);
    }

    private boolean isRestrictedAddress(InetAddress address) {
        byte[] bytes = address.getAddress();
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return true;
        }
        if (bytes.length == 4) {
            int first = bytes[0] & 0xff;
            int second = bytes[1] & 0xff;
            return (first == 100 && second >= 64 && second <= 127)
                    || (first == 198 && (second == 18 || second == 19));
        }
        return (bytes[0] & 0xfe) == 0xfc || (bytes[0] & 0xff) == 0
                || isIpv4MappedRestricted(bytes);
    }

    private boolean isIpv4MappedRestricted(byte[] bytes) {
        if (bytes.length != 16) {
            return false;
        }
        for (int i = 0; i < 10; i++) {
            if (bytes[i] != 0) {
                return false;
            }
        }
        if (bytes[10] != (byte) 0xff || bytes[11] != (byte) 0xff) {
            return false;
        }
        int first = bytes[12] & 0xff;
        int second = bytes[13] & 0xff;
        return first == 10 || first == 127 || (first == 172 && second >= 16 && second <= 31)
                || first == 192 && second == 168 || first == 169 && second == 254;
    }

    private Set<String> parseAllowedHosts(String value) {
        if (value == null || value.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(value.split(","))
                .map(String::strip)
                .filter(host -> !host.isBlank())
                .map(this::normalizeAllowedHost)
                .filter(host -> !host.isBlank())
                .collect(Collectors.toUnmodifiableSet());
    }

    private String normalizeAllowedHost(String value) {
        try {
            URI uri = value.contains("://") ? URI.create(value) : URI.create("https://" + value);
            return uri.getHost() == null ? value.toLowerCase(Locale.ROOT) : uri.getHost().toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException e) {
            return value.toLowerCase(Locale.ROOT);
        }
    }

    private String normalizeFallback(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    /** 截断响应体,避免将大段或含敏感信息的 API 响应回显给前端用户。 */
    private static String truncateBody(String body) {
        if (body == null) {
            return "";
        }
        int max = 500;
        return body.length() > max ? body.substring(0, max) + "..." : body;
    }
}
