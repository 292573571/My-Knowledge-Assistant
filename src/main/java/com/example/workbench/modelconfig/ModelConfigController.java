package com.example.workbench.modelconfig;

import com.example.workbench.auth.AppUser;
import com.example.workbench.auth.AuthFilter;
import com.example.workbench.audit.AuditAction;
import com.example.workbench.audit.AuditOutcome;
import com.example.workbench.audit.AuditService;
import com.example.workbench.config.HttpRequestLoggingFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/model-config")
public class ModelConfigController {

    private final AiModelService aiModelService;
    private final UserModelConfigService userModelConfigService;
    private final AuditService auditService;

    public ModelConfigController(AiModelService aiModelService, UserModelConfigService userModelConfigService,
                                 AuditService auditService) {
        this.aiModelService = aiModelService;
        this.userModelConfigService = userModelConfigService;
        this.auditService = auditService;
    }

    @GetMapping("/pool")
    public List<AiModelResponse> pool(HttpServletRequest request) {
        return aiModelService.list();
    }

    @PostMapping("/pool")
    @ResponseStatus(HttpStatus.CREATED)
    public AiModelResponse create(@Valid @RequestBody AiModelRequest body, HttpServletRequest request) {
        return audited(request, AuditAction.MODEL_CONFIG_CREATE, "pending", () -> {
            AiModelResponse result = aiModelService.create(user(request), body);
            audit(request, AuditAction.MODEL_CONFIG_CREATE, String.valueOf(result.id()), AuditOutcome.SUCCESS, "NONE");
            return result;
        });
    }

    @PutMapping("/pool/{id}")
    public AiModelResponse update(@PathVariable Long id, @Valid @RequestBody AiModelRequest body,
                                  HttpServletRequest request) {
        return audited(request, AuditAction.MODEL_CONFIG_UPDATE, String.valueOf(id), () -> {
            AiModelResponse result = aiModelService.update(user(request), id, body);
            audit(request, AuditAction.MODEL_CONFIG_UPDATE, String.valueOf(id), AuditOutcome.SUCCESS, "NONE");
            return result;
        });
    }

    @DeleteMapping("/pool/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id, HttpServletRequest request) {
        audited(request, AuditAction.MODEL_CONFIG_DELETE, String.valueOf(id), () -> {
            aiModelService.delete(user(request), id);
            audit(request, AuditAction.MODEL_CONFIG_DELETE, String.valueOf(id), AuditOutcome.SUCCESS, "NONE");
            return null;
        });
    }

    @PutMapping("/pool/{id}/default")
    public AiModelResponse setDefault(@PathVariable Long id, HttpServletRequest request) {
        return audited(request, AuditAction.MODEL_CONFIG_DEFAULT_CHANGE, String.valueOf(id), () -> {
            AiModelResponse result = aiModelService.setDefault(user(request), id);
            audit(request, AuditAction.MODEL_CONFIG_DEFAULT_CHANGE, String.valueOf(id), AuditOutcome.SUCCESS, "NONE");
            return result;
        });
    }

    @PostMapping("/pool/{id}/test")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void test(@PathVariable Long id, HttpServletRequest request) {
        aiModelService.testConnect(user(request), id);
    }

    @GetMapping("/me")
    public UserModelConfigResponse get(HttpServletRequest request) {
        return userModelConfigService.get(user(request));
    }

    @PutMapping("/me")
    public UserModelConfigResponse save(@Valid @RequestBody UserModelConfigRequest body, HttpServletRequest request) {
        return audited(request, AuditAction.PERSONAL_MODEL_CONFIG_CHANGE, String.valueOf(user(request).getPublicId()), () -> {
            UserModelConfigResponse result = userModelConfigService.save(user(request), body);
            audit(request, AuditAction.PERSONAL_MODEL_CONFIG_CHANGE, user(request).getPublicId(), AuditOutcome.SUCCESS, "NONE");
            return result;
        });
    }

    private AppUser user(HttpServletRequest request) {
        AppUser user = (AppUser) request.getAttribute(AuthFilter.AUTHENTICATED_USER_ATTRIBUTE);
        if (user == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请先登录");
        return user;
    }

    private <T> T audited(HttpServletRequest request, AuditAction action, String resourceId, Action<T> operation) {
        try {
            return operation.run();
        } catch (RuntimeException exception) {
            audit(request, action, resourceId, auditService.outcome(exception), auditService.reasonCode(exception));
            throw exception;
        }
    }

    private void audit(HttpServletRequest request, AuditAction action, String resourceId, AuditOutcome outcome,
                       String reasonCode) {
        try {
            auditService.record(user(request), "system", action, "MODEL_CONFIG", resourceId, outcome, reasonCode,
                    requestId(request));
        } catch (RuntimeException ignored) {
        }
    }

    private String requestId(HttpServletRequest request) {
        Object value = request.getAttribute(HttpRequestLoggingFilter.REQUEST_ID_ATTRIBUTE);
        return value == null ? "unknown" : value.toString();
    }

    @FunctionalInterface
    private interface Action<T> {
        T run();
    }
}
