package com.example.workbench.learningassistant;

import com.example.workbench.auth.AppUser;
import com.example.workbench.auth.AuthFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/learning-assistant/sessions")
public class LearningAssistantController {
    private static final long STREAM_TIMEOUT_MS = 120_000L;
    private final LearningAssistantService service;

    public LearningAssistantController(LearningAssistantService service) {
        this.service = service;
    }

    @PostMapping
    public LearningAssistantSessionResponse create(@Valid @RequestBody LearningAssistantSessionRequest request,
                                                   HttpServletRequest httpRequest) {
        return service.createSession(user(httpRequest), request);
    }

    @GetMapping
    public List<LearningAssistantSessionResponse> list(@RequestParam(required = false) String workspaceId,
                                                       HttpServletRequest httpRequest) {
        return service.listSessions(user(httpRequest), workspaceId);
    }

    @GetMapping("/{sessionId}")
    public LearningAssistantSessionResponse get(@PathVariable String sessionId,
                                                @RequestParam(required = false) String workspaceId,
                                                HttpServletRequest httpRequest) {
        return service.getSession(user(httpRequest), sessionId, workspaceId);
    }

    @DeleteMapping("/{sessionId}")
    @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String sessionId,
                       @RequestParam(required = false) String workspaceId,
                       HttpServletRequest httpRequest) {
        service.deleteSession(user(httpRequest), sessionId, workspaceId);
    }

    @PostMapping("/{sessionId}/messages")
    public LearningAssistantResponse message(@PathVariable String sessionId,
                                             @Valid @RequestBody LearningAssistantMessageRequest request,
                                             HttpServletRequest httpRequest) {
        return service.message(user(httpRequest), sessionId, request);
    }

    @PostMapping(path = "/{sessionId}/messages/stream", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable String sessionId,
                             @Valid @RequestBody LearningAssistantMessageRequest request,
                             HttpServletRequest httpRequest) {
        AppUser user = user(httpRequest);
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MS);
        CompletableFuture.runAsync(() -> {
            try {
                LearningAssistantResponse response = service.message(user, sessionId, request);
                send(emitter, "session", Map.of("sessionId", response.sessionId()));
                send(emitter, "message_start", Map.of("intent", response.intent().name(), "mode", response.mode().name()));
                if (response.answer() != null && !response.answer().isBlank()) {
                    send(emitter, "token", Map.of("text", response.answer()));
                }
                if (response.sources() != null) {
                    for (Object source : response.sources()) send(emitter, "source", source);
                }
                if (response.stage() != null) {
                    Map<String, Object> stage = new java.util.HashMap<>();
                    stage.put("stage", response.stage());
                    stage.put("nextAction", response.nextAction());
                    send(emitter, "teaching_stage", stage);
                }
                if (response.check() != null) send(emitter, "check", response.check());
                if (response.practice() != null) send(emitter, "practice", response.practice());
                send(emitter, "done", Map.of("response", response));
                emitter.complete();
            } catch (Exception exception) {
                try {
                    send(emitter, "error", Map.of("message", "请求失败，请稍后重试"));
                } catch (Exception ignored) {
                    // 浏览器可能已关闭连接。
                }
                emitter.completeWithError(exception);
            }
        });
        return emitter;
    }

    @PostMapping("/{sessionId}/check")
    public LearningAssistantResponse check(@PathVariable String sessionId,
                                           @Valid @RequestBody LearningAssistantCheckRequest request,
                                           HttpServletRequest httpRequest) {
        return service.check(user(httpRequest), sessionId, request);
    }

    @PostMapping("/{sessionId}/practice")
    public LearningAssistantResponse practice(@PathVariable String sessionId,
                                              @Valid @RequestBody LearningAssistantPracticeRequest request,
                                              HttpServletRequest httpRequest) {
        return service.practice(user(httpRequest), sessionId, request);
    }

    private AppUser user(HttpServletRequest request) {
        AppUser user = (AppUser) request.getAttribute(AuthFilter.AUTHENTICATED_USER_ATTRIBUTE);
        if (user == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请先登录");
        return user;
    }

    private void send(SseEmitter emitter, String event, Object data) throws java.io.IOException {
        emitter.send(SseEmitter.event().name(event).data(data));
    }
}
