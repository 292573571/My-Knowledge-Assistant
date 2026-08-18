package com.example.workbench.modelconfig;

import com.example.workbench.auth.AppUser;
import com.example.workbench.auth.AuthFilter;
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

    public ModelConfigController(AiModelService aiModelService, UserModelConfigService userModelConfigService) {
        this.aiModelService = aiModelService;
        this.userModelConfigService = userModelConfigService;
    }

    @GetMapping("/pool")
    public List<AiModelResponse> pool(HttpServletRequest request) {
        return aiModelService.list();
    }

    @PostMapping("/pool")
    @ResponseStatus(HttpStatus.CREATED)
    public AiModelResponse create(@Valid @RequestBody AiModelRequest body, HttpServletRequest request) {
        return aiModelService.create(user(request), body);
    }

    @PutMapping("/pool/{id}")
    public AiModelResponse update(@PathVariable Long id, @Valid @RequestBody AiModelRequest body,
                                  HttpServletRequest request) {
        return aiModelService.update(user(request), id, body);
    }

    @DeleteMapping("/pool/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id, HttpServletRequest request) {
        aiModelService.delete(user(request), id);
    }

    @PutMapping("/pool/{id}/default")
    public AiModelResponse setDefault(@PathVariable Long id, HttpServletRequest request) {
        return aiModelService.setDefault(user(request), id);
    }

    @GetMapping("/me")
    public UserModelConfigResponse get(HttpServletRequest request) {
        return userModelConfigService.get(user(request));
    }

    @PutMapping("/me")
    public UserModelConfigResponse save(@Valid @RequestBody UserModelConfigRequest body, HttpServletRequest request) {
        return userModelConfigService.save(user(request), body);
    }

    private AppUser user(HttpServletRequest request) {
        AppUser user = (AppUser) request.getAttribute(AuthFilter.AUTHENTICATED_USER_ATTRIBUTE);
        if (user == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请先登录");
        return user;
    }
}
