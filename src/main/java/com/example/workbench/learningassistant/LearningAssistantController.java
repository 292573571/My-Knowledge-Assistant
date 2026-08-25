package com.example.workbench.learningassistant;

import com.example.workbench.auth.AppUser;
import com.example.workbench.auth.AuthFilter;
import com.example.workbench.conversation.ConversationExecutionRegistry;
import com.example.workbench.conversation.ConversationService;
import com.example.workbench.config.HttpRequestLoggingFilter;
import com.example.workbench.config.ModelProviderException;
import com.example.workbench.modelconfig.ModelConfigContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Executor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
    private static final Logger log = LoggerFactory.getLogger(LearningAssistantController.class);
    private static final long STREAM_TIMEOUT_MS = 120_000L;
    private final LearningAssistantService service;
    private final ConversationExecutionRegistry executionRegistry;
    private final ConversationService conversationService;
    private final ModelConfigContext modelConfigContext;
    private Executor streamExecutor = Runnable::run;

    public LearningAssistantController(LearningAssistantService service,
                                       ConversationExecutionRegistry executionRegistry,
                                       ConversationService conversationService,
                                       ModelConfigContext modelConfigContext) {
        this.service = service;
        this.executionRegistry = executionRegistry;
        this.conversationService = conversationService;
        this.modelConfigContext = modelConfigContext;
    }

    @Autowired(required = false)
    public void setStreamExecutor(@Qualifier("streamTaskExecutor") Executor streamExecutor) {
        this.streamExecutor = streamExecutor;
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
        AppUser currentUser = user(httpRequest);
            modelConfigContext.set(currentUser.getId(), currentUser.getPublicId(), request.modelId());
        try {
            return service.message(currentUser, sessionId, request);
        } finally {
            modelConfigContext.clear();
        }
    }

    @PostMapping(path = "/{sessionId}/messages/stream", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable String sessionId,
                             @Valid @RequestBody LearningAssistantMessageRequest request,
                             HttpServletRequest httpRequest,
                             jakarta.servlet.http.HttpServletResponse httpResponse) {
        AppUser user = user(httpRequest);
        String workspaceId = request.workspaceId();
        // 显式禁止代理、网关或压缩层缓冲 SSE，确保每个 token 抵达后立即交给浏览器。
        httpResponse.setHeader("Cache-Control", "no-cache, no-transform");
        httpResponse.setHeader("X-Accel-Buffering", "no");
        httpResponse.setCharacterEncoding("UTF-8");
        service.validateSession(user, sessionId, workspaceId);
        String scope = conversationService.executionScope(user, workspaceId, sessionId);
        Object requestIdAttribute = httpRequest.getAttribute(HttpRequestLoggingFilter.REQUEST_ID_ATTRIBUTE);
        String requestId = requestIdAttribute == null ? "" : requestIdAttribute.toString();
        ConversationExecutionRegistry.Execution execution = executionRegistry.begin(scope);
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MS);
        emitter.onTimeout(() -> executionRegistry.cancel(scope));
        emitter.onError(error -> executionRegistry.cancel(scope));
        emitter.onCompletion(() -> executionRegistry.cancel(scope));
        CompletableFuture.runAsync(() -> {
            modelConfigContext.set(user.getId(), user.getPublicId(), request.modelId());
            try {
                send(emitter, "session", Map.of("sessionId", sessionId));
                // 检索进度事件：RAG 前置处理（检索/改写）期间让前端不再黑屏转圈。
                send(emitter, "tool_call_start", Map.of(
                        "id", "tool-rag-retrieve",
                        "toolName", "rag_retrieve",
                        "arguments", Map.of("message", request.message()),
                        "status", "running"));
                LearningAssistantResponse response = service.streamMessage(user, sessionId, request,
                        token -> {
                            if (execution.isCancelled() || Thread.currentThread().isInterrupted()) return;
                            try {
                                send(emitter, "token", Map.of("text", token));
                            } catch (java.io.IOException ignored) {
                                Thread.currentThread().interrupt();
                            }
                        },
                        sources -> {
                            if (execution.isCancelled()) return;
                            try {
                                send(emitter, "tool_call_result", Map.of(
                                        "id", "tool-rag-retrieve",
                                        "toolName", "rag_retrieve",
                                        "success", true,
                                        "status", "success",
                                        "resultPreview", "已检索到 " + (sources == null ? 0 : sources.size()) + " 个相关片段"));
                                if (sources != null) {
                                    for (Object source : sources) send(emitter, "source", source);
                                }
                            } catch (java.io.IOException ignored) {
                                Thread.currentThread().interrupt();
                            }
                        },
                        execution);
                if (execution.isCancelled()) throw new CancellationException("学习请求已停止");
                send(emitter, "done", Map.of("response", response));
                emitter.complete();
            } catch (CancellationException exception) {
                emitter.complete();
            } catch (Exception exception) {
                log.error("统一学习助手流式请求失败 sessionId={} errorType={} message={}",
                        sessionId, exception.getClass().getSimpleName(), exception.getMessage(), exception);
                try {
                    send(emitter, "error", errorPayload(exception, requestId));
                } catch (Exception ignored) {
                    // 浏览器可能已关闭连接。
                }
                emitter.completeWithError(exception);
            } finally {
                executionRegistry.finish(scope, execution);
                modelConfigContext.clear();
            }
        }, streamExecutor);
        return emitter;
    }

    @PostMapping("/{sessionId}/check")
    public LearningAssistantResponse check(@PathVariable String sessionId,
                                           @Valid @RequestBody LearningAssistantCheckRequest request,
                                           HttpServletRequest httpRequest) {
        AppUser currentUser = user(httpRequest);
        modelConfigContext.set(currentUser.getId(), currentUser.getPublicId(), request.modelId());
        try {
            return service.check(currentUser, sessionId, request);
        } finally {
            modelConfigContext.clear();
        }
    }

    @PostMapping("/{sessionId}/practice")
    public LearningAssistantResponse practice(@PathVariable String sessionId,
                                              @Valid @RequestBody LearningAssistantPracticeRequest request,
                                              HttpServletRequest httpRequest) {
        AppUser currentUser = user(httpRequest);
        modelConfigContext.set(currentUser.getId(), currentUser.getPublicId(), request.modelId());
        try {
            return service.practice(currentUser, sessionId, request);
        } finally {
            modelConfigContext.clear();
        }
    }

    @PostMapping("/{sessionId}/stop")
    @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.NO_CONTENT)
    public void stop(@PathVariable String sessionId,
                     @RequestParam(required = false) String workspaceId,
                     HttpServletRequest httpRequest) {
        service.stop(user(httpRequest), sessionId, workspaceId);
    }

    private AppUser user(HttpServletRequest request) {
        AppUser user = (AppUser) request.getAttribute(AuthFilter.AUTHENTICATED_USER_ATTRIBUTE);
        if (user == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请先登录");
        return user;
    }

    private void send(SseEmitter emitter, String event, Object data) throws java.io.IOException {
        emitter.send(SseEmitter.event().name(event).data(data));
    }

    private Map<String, Object> errorPayload(Exception exception, String requestId) {
        Map<String, Object> payload = new java.util.HashMap<>();
        if (exception instanceof ModelProviderException provider) {
            payload.put("message", provider.getUserMessage());
            payload.put("errorType", provider.getErrorCode());
            payload.put("status", provider.getHttpStatus());
            payload.put("retryable", provider.isRetryable());
            payload.put("requestId", requestId);
            if (provider.getTraceId() != null) {
                payload.put("traceId", provider.getTraceId());
            }
            return payload;
        }
        payload.put("message", exception instanceof ResponseStatusException response && response.getReason() != null
                ? response.getReason() : "学习助手处理失败，请稍后重试");
        payload.put("errorType", exception.getClass().getSimpleName());
        payload.put("requestId", requestId);
        if (exception instanceof ResponseStatusException response) {
            int status = response.getStatusCode().value();
            payload.put("status", status);
            payload.put("retryable", status == 408 || status == 429 || status >= 500);
        }
        return payload;
    }
}
