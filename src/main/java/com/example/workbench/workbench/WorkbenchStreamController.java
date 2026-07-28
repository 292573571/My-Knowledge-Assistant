package com.example.workbench.workbench;

import com.example.workbench.auth.AppUser;
import com.example.workbench.auth.AuthFilter;
import com.example.workbench.auth.UserConversationScope;
import com.example.workbench.conversation.ConversationService;
import com.example.workbench.conversation.ConversationExecutionRegistry;
import com.example.workbench.memory.ConversationMemory;
import com.example.workbench.learning.LearningRecordService;
import com.example.workbench.rag.RagChatRequest;
import com.example.workbench.rag.RagChatResponse;
import com.example.workbench.rag.RagService;
import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
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

    private final RagService ragService;
    private final WorkbenchChatService workbenchChatService;
    private final ConversationService conversationService;
    private final ConversationExecutionRegistry executionRegistry;
    private final ConversationMemory conversationMemory;
    private final LearningRecordService learningRecordService;

    public WorkbenchStreamController(
            RagService ragService,
            WorkbenchChatService workbenchChatService,
            ConversationService conversationService,
            ConversationExecutionRegistry executionRegistry,
            ConversationMemory conversationMemory,
            LearningRecordService learningRecordService
    ) {
        this.ragService = ragService;
        this.workbenchChatService = workbenchChatService;
        this.conversationService = conversationService;
        this.executionRegistry = executionRegistry;
        this.conversationMemory = conversationMemory;
        this.learningRecordService = learningRecordService;
    }

    @PostMapping
    public WorkbenchChatResponse chat(@Valid @RequestBody WorkbenchChatRequest request, HttpServletRequest httpRequest) {
        return workbenchChatService.chat(user(httpRequest), request);
    }

    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(
            @RequestParam(defaultValue = "default") String conversationId,
            @RequestParam(defaultValue = "rag") String mode,
            @RequestParam String message,
            HttpServletRequest httpRequest
    ) {
        AppUser user = user(httpRequest);
        // SSE 连接只负责事件传输；实际 RAG 和消息持久化在异步任务中执行。
        SseEmitter emitter = new SseEmitter(TIMEOUT_MS);
        String normalizedConversationId = conversationId.isBlank() ? "default" : conversationId;
        String scopedConversationId = UserConversationScope.id(user, normalizedConversationId);
        // 与普通聊天使用同一取消注册表，保证停止/删除语义在两条路径一致。
        ConversationExecutionRegistry.Execution execution = executionRegistry.begin(scopedConversationId);

        CompletableFuture.runAsync(() -> {
            long startedAt = System.currentTimeMillis();

            try {
                String normalizedMode = "rag";
                conversationService.recordUserMessage(
                        user,
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

                WorkbenchAnswer answer = answer(UserConversationScope.id(user, normalizedConversationId), normalizedMode, message);
                if (execution.isCancelled()) {
                    // 在检索或模型生成期间取消时，直接结束 SSE，且不发送/保存迟到结果。
                    emitter.complete();
                    return;
                }
                long durationMs = System.currentTimeMillis() - startedAt;

                send(emitter, "tool_call_result", Map.of(
                        "id", "tool-rag-retrieve",
                        "toolName", "rag_retrieve",
                        "success", true,
                        "status", "success",
                        "durationMs", durationMs,
                        "resultPreview", preview(answer.answer())
                ));

                for (Object source : answer.sources()) {
                    send(emitter, "source", source);
                }

                // 当前按字符模拟推送完整回答；后续可替换为模型原生 token 流而不改变事件协议。
                streamTokens(emitter, answer.answer());
                if (!execution.isCancelled()) {
                    boolean recorded = conversationService.recordAssistantMessage(user, normalizedConversationId, normalizedMode, answer.answer(), answer.sources(), java.util.List.of());
                    if (recorded) {
                        // 只收录真实保存成功的回答，避免已删除会话被学习记录重新引用。
                        learningRecordService.record(user, message, answer.answer(), answer.sources());
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
            }
        });

        return emitter;
    }

    private WorkbenchAnswer answer(String conversationId, String mode, String message) {
        // mode 目前固定为 rag；保留参数是为了不破坏现有流式调用边界。
        RagChatResponse response = ragService.chat(new RagChatRequest(conversationId, message));
        return new WorkbenchAnswer(response.answer(), response.sources());
    }

    private void streamTokens(SseEmitter emitter, String answer) throws IOException, InterruptedException {
        for (int index = 0; index < answer.length(); index++) {
            send(emitter, "token", Map.of("text", String.valueOf(answer.charAt(index))));
            Thread.sleep(8L);
        }
    }

    private String preview(String answer) {
        if (answer == null || answer.length() <= 120) {
            return answer == null ? "" : answer;
        }

        return answer.substring(0, 120) + "...";
    }

    private void send(SseEmitter emitter, String eventName, Object data) throws IOException {
        emitter.send(SseEmitter.event().name(eventName).data(data));
    }

    private AppUser user(HttpServletRequest request) {
        return (AppUser) request.getAttribute(AuthFilter.AUTHENTICATED_USER_ATTRIBUTE);
    }
}
