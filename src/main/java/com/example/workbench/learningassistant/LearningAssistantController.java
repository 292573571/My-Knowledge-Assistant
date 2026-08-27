package com.example.workbench.learningassistant;

import com.example.workbench.auth.AppUser;
import com.example.workbench.auth.AuthFilter;
import com.example.workbench.conversation.ConversationExecutionRegistry;
import com.example.workbench.conversation.ConversationService;
import com.example.workbench.config.HttpRequestLoggingFilter;
import com.example.workbench.config.ModelProviderException;
import com.example.workbench.modelconfig.ModelConfigContext;
import com.example.workbench.rag.RagSource;
import com.example.workbench.streaming.StreamChunk;
import com.example.workbench.streaming.StreamSession;
import com.example.workbench.streaming.StreamSessionStore;
import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/learning-assistant/sessions")
public class LearningAssistantController {
    private static final Logger log = LoggerFactory.getLogger(LearningAssistantController.class);
    private static final long STREAM_TIMEOUT_MS = 300_000L;
    private final LearningAssistantService service;
    private final ConversationExecutionRegistry executionRegistry;
    private final ConversationService conversationService;
    private final ModelConfigContext modelConfigContext;
    private final StreamSessionStore streamStore;
    private final long heartbeatMs;
    private final ScheduledExecutorService heartbeatScheduler = Executors.newScheduledThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "sse-heartbeat");
        thread.setDaemon(true);
        return thread;
    });
    private Executor streamExecutor = Runnable::run;

    public LearningAssistantController(LearningAssistantService service,
                                       ConversationExecutionRegistry executionRegistry,
                                       ConversationService conversationService,
                                       ModelConfigContext modelConfigContext,
                                       StreamSessionStore streamStore,
                                       @Value("${app.ai.stream.heartbeat-ms:15000}") long heartbeatMs) {
        this.service = service;
        this.executionRegistry = executionRegistry;
        this.conversationService = conversationService;
        this.modelConfigContext = modelConfigContext;
        this.streamStore = streamStore;
        this.heartbeatMs = Math.max(5000, heartbeatMs);
    }

    @PreDestroy
    public void shutdownHeartbeat() {
        heartbeatScheduler.shutdownNow();
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
    public Object list(@RequestParam(required = false) String workspaceId,
                                                       @RequestParam(required = false) Integer page,
                                                       @RequestParam(required = false) Integer size,
                                                       HttpServletRequest httpRequest) {
        AppUser currentUser = user(httpRequest);
        return page == null && size == null ? service.listSessions(currentUser, workspaceId)
                : service.pageSessions(currentUser, workspaceId, page == null ? 0 : page, size == null ? 100 : size);
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

    /**
     * 弹性流式回答端点。
     *
     * <p>设计核心:生成任务与 SSE 推送解耦。每个请求以 {@code streamId}(默认即 clientRequestId)在
     * {@link StreamSessionStore} 中对应一个 {@link StreamSession}:首访负责启动生成并写入有序片段,
     * 后续访问(含断线重连)只作为订阅者,从历史断点({@code Last-Event-ID})续传,绝不重复生成。</p>
     *
     * <p>客户端断开时只解除当前连接的推送,生成任务继续跑完并写入缓冲,因此用户重连即可无感接回,
     * 彻底解决"答到一半中断、半截答案丢失"的体验问题。</p>
     */
    @PostMapping(path = "/{sessionId}/messages/stream", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable String sessionId,
                             @Valid @RequestBody LearningAssistantMessageRequest request,
                             HttpServletRequest httpRequest,
                             HttpServletResponse httpResponse,
                             @RequestParam(value = "streamId", required = false) String requestStreamId,
                             @RequestHeader(value = "Last-Event-ID", required = false) String lastEventIdHeader) {
        AppUser user = user(httpRequest);
        String workspaceId = request.workspaceId();
        // 显式禁止代理、网关或压缩层缓冲 SSE，确保每个 token 抵达后立即交给浏览器。
        httpResponse.setHeader("Cache-Control", "no-cache, no-transform");
        httpResponse.setHeader("X-Accel-Buffering", "no");
        httpResponse.setCharacterEncoding("UTF-8");
        service.validateSession(user, sessionId, workspaceId);

        // 续传标识:优先用客户端显式传入的 streamId,否则回退到 clientRequestId,最后由服务端生成并随 stream_init 下发。
        String streamId = resolveStreamId(requestStreamId, request);
        long resumeSeq = parseLastEventId(lastEventIdHeader);

        StreamSession session = streamStore.get(streamId, user.getId());
        boolean owner = false;
        if (session == null || session.status() == StreamSession.Status.FAILED) {
            streamStore.remove(streamId);
            session = streamStore.create(streamId, user.getId());
            owner = true;
        }

        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MS);
        attach(emitter, session, resumeSeq);
        if (owner) {
            startGeneration(emitter, session, user, sessionId, request, workspaceId, streamId, httpRequest);
        }
        return emitter;
    }

    /** 把 SSE 发射器接入已有 StreamSession:重放断点之后历史 + 实时订阅后续片段 + 心跳保活。 */
    private void attach(SseEmitter emitter, StreamSession session, long resumeSeq) {
        AtomicBoolean closed = new AtomicBoolean(false);
        ScheduledFuture<?> heartbeat = heartbeatScheduler.scheduleAtFixedRate(() -> {
            if (closed.get()) return;
            try {
                emitter.send(SseEmitter.event().comment("keepalive"));
            } catch (IOException | IllegalStateException ignored) {
                // 连接已断,onError/onCompletion 会取消心跳任务。
            }
        }, heartbeatMs, heartbeatMs, TimeUnit.MILLISECONDS);

        emitter.onCompletion(() -> {
            closed.set(true);
            heartbeat.cancel(true);
        });
        emitter.onTimeout(() -> {
            closed.set(true);
            heartbeat.cancel(true);
        });
        emitter.onError(error -> {
            closed.set(true);
            heartbeat.cancel(true);
        });

        // subscribe 内部在同一把锁里先重放 resumeSeq 之后的历史、再注册实时订阅,
        // 因此每个片段恰好投递一次且严格保序;终态回调只负责关闭连接(终态片段本身已由 onChunk 投递)。
        session.subscribe(resumeSeq,
                chunk -> deliver(emitter, chunk, closed),
                terminal -> closeQuietly(emitter, closed));
    }

    /** 会话已终结时干净地收尾;若连接已关闭则什么都不做。 */
    private void closeQuietly(SseEmitter emitter, AtomicBoolean closed) {
        if (closed.getAndSet(true)) return;
        try {
            emitter.complete();
        } catch (IllegalStateException ignored) {
            // 连接已断开或已结束,忽略
        }
    }

    /** 向发射器投递一个片段;终态片段发送后干净地关闭连接。 */
    private void deliver(SseEmitter emitter, StreamChunk chunk, AtomicBoolean closed) {
        if (closed.get()) return;
        boolean terminal = chunk.isTerminal();
        try {
            send(emitter, chunk);
        } catch (IOException | IllegalStateException ignored) {
            closed.set(true);
            return;
        }
        if (terminal) {
            closed.set(true);
            try {
                emitter.complete();
            } catch (IllegalStateException ignored) {
                // 连接已断开或已结束,忽略
            }
        }
    }

    /** 作为 owner 启动生成任务,把每个事件有序写入 StreamSession,结束(成功/取消/失败)时标记终态。 */
    private void startGeneration(SseEmitter emitter, StreamSession session, AppUser user, String sessionId,
                                 LearningAssistantMessageRequest request, String workspaceId, String streamId,
                                 HttpServletRequest httpRequest) {
        String scope = conversationService.executionScope(user, workspaceId, sessionId);
        Object requestIdAttribute = httpRequest.getAttribute(HttpRequestLoggingFilter.REQUEST_ID_ATTRIBUTE);
        String requestId = requestIdAttribute == null ? "" : requestIdAttribute.toString();
        ConversationExecutionRegistry.Execution execution = executionRegistry.begin(scope);
        CompletableFuture.runAsync(() -> {
            modelConfigContext.set(user.getId(), user.getPublicId(), request.modelId());
            try {
                session.append("session", Map.of("sessionId", sessionId));
                session.append("stream_init", Map.of("streamId", streamId, "resumeSupported", true));
                // 检索进度事件：RAG 前置处理（检索/改写）期间让前端不再黑屏转圈。
                session.append("tool_call_start", Map.of(
                        "id", "tool-rag-retrieve",
                        "toolName", "rag_retrieve",
                        "arguments", Map.of("message", request.message()),
                        "status", "running"));
                LearningAssistantResponse response = service.streamMessage(user, sessionId, request,
                        token -> {
                            if (execution.isCancelled() || Thread.currentThread().isInterrupted()) return;
                            session.append("token", Map.of("text", token));
                        },
                        sources -> {
                            if (execution.isCancelled()) return;
                            session.append("tool_call_result", Map.of(
                                    "id", "tool-rag-retrieve",
                                    "toolName", "rag_retrieve",
                                    "success", true,
                                    "status", "success",
                                    "resultPreview", "已检索到 " + (sources == null ? 0 : sources.size()) + " 个相关片段"));
                            if (sources != null) {
                                for (Object source : sources) session.append("source", source);
                            }
                        },
                        execution);
                if (execution.isCancelled()) throw new CancellationException("学习请求已停止");
                StreamChunk done = session.append("done", Map.of("response", response));
                session.markDone(done);
            } catch (CancellationException exception) {
                // 用户主动停止:干净收尾,不抛错误,已产生的半截内容交由前端保留。
                StreamChunk done = session.append("done", Map.of("interrupted", true, "response", null));
                session.markDone(done);
            } catch (Exception exception) {
                log.warn("统一学习助手流式请求失败 sessionId={} streamId={} errorType={} message={}",
                        sessionId, streamId, exception.getClass().getSimpleName(), exception.getMessage(), exception);
                StreamChunk error = session.append("error", errorPayload(exception, requestId));
                session.markFailed(error);
            } finally {
                executionRegistry.finish(scope, execution);
                modelConfigContext.clear();
            }
        }, streamExecutor);
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

    private String resolveStreamId(String requestStreamId, LearningAssistantMessageRequest request) {
        if (requestStreamId != null && !requestStreamId.isBlank()) return requestStreamId;
        if (request.clientRequestId() != null && !request.clientRequestId().isBlank()) return request.clientRequestId();
        return UUID.randomUUID().toString();
    }

    private long parseLastEventId(String header) {
        if (header == null || header.isBlank()) return 0L;
        try {
            return Long.parseLong(header.trim());
        } catch (NumberFormatException exception) {
            return 0L;
        }
    }

    private void send(SseEmitter emitter, StreamChunk chunk) throws IOException {
        emitter.send(SseEmitter.event().id(String.valueOf(chunk.seq())).name(chunk.event()).data(chunk.data()));
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
        // 区分模型超时与一般异常，前端可给更具体的提示。
        if (isTimeoutException(exception)) {
            payload.put("message", "模型响应超时,请稍后重试或切换其他模型");
            payload.put("errorType", "timeout");
            payload.put("retryable", true);
            payload.put("requestId", requestId);
            return payload;
        }
        payload.put("message", exception instanceof ResponseStatusException response && response.getReason() != null
                ? response.getReason() : "学习助手处理失败,请稍后重试");
        payload.put("errorType", exception.getClass().getSimpleName());
        payload.put("requestId", requestId);
        if (exception instanceof ResponseStatusException response) {
            int status = response.getStatusCode().value();
            payload.put("status", status);
            payload.put("retryable", status == 408 || status == 429 || status >= 500);
        }
        return payload;
    }

    private static boolean isTimeoutException(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            String name = current.getClass().getName();
            if (name.equals("java.util.concurrent.TimeoutException")
                    || name.equals("reactor.core.Exceptions$ReactiveException")
                            && current.getMessage() != null
                            && current.getMessage().contains("TimeoutException")) {
                return true;
            }
            if (current instanceof java.util.concurrent.TimeoutException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
