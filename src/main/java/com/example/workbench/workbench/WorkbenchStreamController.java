package com.example.workbench.workbench;

import com.example.workbench.auth.AppUser;
import com.example.workbench.auth.AuthFilter;
import com.example.workbench.auth.UserConversationScope;
import com.example.workbench.conversation.ConversationService;
import com.example.workbench.conversation.ConversationExecutionRegistry;
import com.example.workbench.memory.ConversationMemory;
import com.example.workbench.learning.LearningRecordService;
import com.example.workbench.modelconfig.ModelConfigContext;
import com.example.workbench.rag.RagChatRequest;
import com.example.workbench.rag.RagService;
import com.example.workbench.rag.RagStreamResponse;
import com.example.workbench.rag.RagQualityAuditService;
import com.example.workbench.workspace.WorkspaceAccessContext;
import com.example.workbench.workspace.WorkspaceService;
import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Map;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/workbench/chat")
public class WorkbenchStreamController {

    private static final long TIMEOUT_MS = 120_000L;
    private static final Logger log = LoggerFactory.getLogger(WorkbenchStreamController.class);

    private final RagService ragService;
    private final WorkbenchChatService workbenchChatService;
    private final ConversationService conversationService;
    private final ConversationExecutionRegistry executionRegistry;
    private final ConversationMemory conversationMemory;
    private final LearningRecordService learningRecordService;
    private final RagQualityAuditService ragQualityAuditService;
    private final WorkspaceService workspaceService;
    private final ModelConfigContext modelConfigContext;

    public WorkbenchStreamController(
            RagService ragService,
            WorkbenchChatService workbenchChatService,
            ConversationService conversationService,
            ConversationExecutionRegistry executionRegistry,
            ConversationMemory conversationMemory,
            LearningRecordService learningRecordService,
            RagQualityAuditService ragQualityAuditService,
            WorkspaceService workspaceService,
            ModelConfigContext modelConfigContext
    ) {
        this.ragService = ragService;
        this.workbenchChatService = workbenchChatService;
        this.conversationService = conversationService;
        this.executionRegistry = executionRegistry;
        this.conversationMemory = conversationMemory;
        this.learningRecordService = learningRecordService;
        this.ragQualityAuditService = ragQualityAuditService;
        this.workspaceService = workspaceService;
        this.modelConfigContext = modelConfigContext;
    }

    @PostMapping
    public WorkbenchChatResponse chat(@Valid @RequestBody WorkbenchChatRequest request, HttpServletRequest httpRequest) {
        return workbenchChatService.chat(user(httpRequest), request);
    }

    @PostMapping(path = "/stream", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(
            @Valid @RequestBody WorkbenchChatRequest request,
            HttpServletRequest httpRequest,
            jakarta.servlet.http.HttpServletResponse httpResponse
    ) {
        AppUser user = user(httpRequest);
        String conversationId = request.normalizedConversationId();
        String mode = request.normalizedMode();
        String message = request.message();
        WorkspaceAccessContext workspace = workspaceService.access(user, request.workspaceId());
        // 显式禁止代理、网关或压缩层缓冲 SSE，确保每个 token 抵达后立即交给浏览器。
        httpResponse.setHeader("Cache-Control", "no-cache, no-transform");
        httpResponse.setHeader("X-Accel-Buffering", "no");
        httpResponse.setCharacterEncoding("UTF-8");
        // SSE 连接只负责事件传输；实际 RAG 和消息持久化在异步任务中执行。
        SseEmitter emitter = new SseEmitter(TIMEOUT_MS);
        String normalizedConversationId = conversationId.isBlank() ? "default" : conversationId;
        String scopedConversationId = conversationService.executionScope(user, workspace.workspaceId(), normalizedConversationId);
        // 与普通聊天使用同一取消注册表，保证停止/删除语义在两条路径一致。
        ConversationExecutionRegistry.Execution execution = executionRegistry.begin(scopedConversationId);

        CompletableFuture.runAsync(() -> {
            long startedAt = System.currentTimeMillis();
            modelConfigContext.set(user.getId());

            try {
                String normalizedMode = "rag";
                conversationService.recordUserMessage(
                        user,
                        workspace.workspaceId(),
                        normalizedConversationId,
                        message.strip().substring(0, Math.min(message.strip().length(), 24)),
                        normalizedMode,
                        message
                );
                send(emitter, "start", Map.of(
                        "conversationId", conversationId,
                        "mode", mode
                ));

                // 前端用工具事件展示当前正在进行知识库检索。
                send(emitter, "tool_call_start", Map.of(
                        "id", "tool-rag-retrieve",
                        "toolName", "rag_retrieve",
                        "arguments", Map.of("message", message),
                        "status", "running"
                ));

                workspaceService.access(user, workspace.workspaceId());
                RagStreamResponse answer = ragService.stream(user,
                        new RagChatRequest(scopedConversationId, workspace.workspaceId(), normalizedConversationId, message));
                if (execution.isCancelled()) {
                    // 在检索或模型生成期间取消时，直接结束 SSE，且不发送/保存迟到结果。
                    emitter.complete();
                    return;
                }
                long durationMs = System.currentTimeMillis() - startedAt;
                workspaceService.access(user, workspace.workspaceId());

                send(emitter, "tool_call_result", Map.of(
                        "id", "tool-rag-retrieve",
                        "toolName", "rag_retrieve",
                        "success", true,
                        "status", "success",
                        "durationMs", durationMs,
                        "resultPreview", "正在生成回答"
                ));

                for (Object source : answer.sources()) {
                    send(emitter, "source", source);
                }

                StringBuilder content = new StringBuilder();
                AtomicBoolean firstTokenSent = new AtomicBoolean(false);
                answer.tokens()
                        .takeWhile(token -> !execution.isCancelled() && !Thread.currentThread().isInterrupted())
                        .doOnNext(token -> {
                            content.append(token);
                            if (firstTokenSent.compareAndSet(false, true)) {
                                log.info("Workbench stream first token forwarded conversationId={} latencyMs={}", scopedConversationId, System.currentTimeMillis() - startedAt);
                            }
                            try {
                                send(emitter, "token", Map.of("text", token));
                            } catch (IOException ignored) {
                                Thread.currentThread().interrupt();
                            }
                        })
                        .blockLast();
                if (!execution.isCancelled()) {
                    workspaceService.access(user, workspace.workspaceId());
                    String answerContent = ragService.sanitizePresentedAnswer(content.toString(), message);
                    boolean recorded = conversationService.recordAssistantMessage(user, workspace.workspaceId(), normalizedConversationId,
                            normalizedMode, answerContent, answer.sources(), List.of());
                        if (recorded) {
                        // 只收录真实保存成功的回答，避免已删除会话被学习记录重新引用。
                            learningRecordService.record(user, workspace.workspaceId(), message, answerContent, answer.sources());
                            ragQualityAuditService.audit(scopedConversationId, message, answerContent, answer.sources());
                    }
                }
                send(emitter, "done", Map.of());
                emitter.complete();
            } catch (Exception error) {
                try {
                    send(emitter, "error", Map.of("message", "请求失败，请稍后重试"));
                } catch (IOException ignored) {
                    // The client may have already closed the connection.
                }
                emitter.completeWithError(error);
            } finally {
                if (execution.isCancelled()) {
                    // 取消时清除短期上下文，删除或停止后不应保留在内存中。
                    conversationMemory.remove(scopedConversationId);
                }
                executionRegistry.finish(scopedConversationId, execution);
                modelConfigContext.clear();
            }
        });

        return emitter;
    }

    private void send(SseEmitter emitter, String eventName, Object data) throws IOException {
        emitter.send(SseEmitter.event().name(eventName).data(data));
    }

    private AppUser user(HttpServletRequest request) {
        return (AppUser) request.getAttribute(AuthFilter.AUTHENTICATED_USER_ATTRIBUTE);
    }
}
