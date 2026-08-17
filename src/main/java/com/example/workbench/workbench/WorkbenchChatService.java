package com.example.workbench.workbench;

import com.example.workbench.auth.AppUser;
import com.example.workbench.auth.UserConversationScope;
import com.example.workbench.conversation.ConversationService;
import com.example.workbench.conversation.ConversationExecutionRegistry;
import com.example.workbench.memory.ConversationMemory;
import com.example.workbench.learning.LearningRecordService;
import com.example.workbench.workspace.WorkspaceAccessContext;
import com.example.workbench.workspace.WorkspaceService;
import com.example.workbench.rag.RagChatRequest;
import com.example.workbench.rag.RagChatResponse;
import com.example.workbench.rag.RagService;
import com.example.workbench.rag.RagStreamResponse;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class WorkbenchChatService {

    private static final Logger log = LoggerFactory.getLogger(WorkbenchChatService.class);

    private final RagService ragService;
    private final ConversationService conversationService;
    private final ConversationExecutionRegistry executionRegistry;
    private final ConversationMemory conversationMemory;
    private final LearningRecordService learningRecordService;
    private final WorkspaceService workspaceService;

    public WorkbenchChatService(
            RagService ragService,
            ConversationService conversationService,
            ConversationExecutionRegistry executionRegistry,
            ConversationMemory conversationMemory,
            LearningRecordService learningRecordService,
            WorkspaceService workspaceService
    ) {
        this.ragService = ragService;
        this.conversationService = conversationService;
        this.executionRegistry = executionRegistry;
        this.conversationMemory = conversationMemory;
        this.learningRecordService = learningRecordService;
        this.workspaceService = workspaceService;
    }

    public WorkbenchChatResponse chat(AppUser user, WorkbenchChatRequest request) {
        long startedAt = System.currentTimeMillis();
        String mode = request.normalizedMode();
        String clientConversationId = request.normalizedConversationId();
        WorkspaceAccessContext workspace = workspaceService.access(user, request.workspaceId());
        String conversationId = conversationService.executionScope(user, workspace.workspaceId(), clientConversationId);

        // 每次生成都注册独立执行对象，停止或删除会话时可标记它取消。
        ConversationExecutionRegistry.Execution execution = executionRegistry.begin(conversationId);
        try {
            // 先保存用户消息，确保模型调用失败时问题仍可在历史会话中查看。
            conversationService.recordUserMessage(
                    user,
                    workspace.workspaceId(),
                    clientConversationId,
                    request.message().strip().substring(0, Math.min(request.message().strip().length(), 24)),
                    mode,
                    request.message()
            );

            log.info(
                    "Workbench chat received userId={} mode={} conversationId={} messageLength={}",
                    user.getId(),
                    mode,
                    conversationId,
                    request.message() == null ? 0 : request.message().length()
            );

            log.info("Workbench chat route selected route=RAG_LOCAL_KNOWLEDGE mode={} conversationId={}", mode, conversationId);
            // 工作台统一走 RAG：优先本地知识库，证据不足时由 RAG 服务决定是否模型补充。
            workspaceService.access(user, workspace.workspaceId());
            RagChatResponse ragResponse = ragService.chat(user,
                    new RagChatRequest(conversationId, workspace.workspaceId(), clientConversationId, request.message()));
            log.info(
                    "Workbench chat completed route=RAG_LOCAL_KNOWLEDGE conversationId={} sources={} durationMs={}",
                    conversationId,
                    ragResponse.sources().size(),
                    System.currentTimeMillis() - startedAt
            );
            WorkbenchChatResponse response = new WorkbenchChatResponse(newMessageId(), ragResponse.answer(), ragResponse.sources(), List.of());
            if (execution.isCancelled()) {
                // 模型返回期间会话可能已停止或删除，此时绝不能把迟到结果写回数据库。
                log.info("Workbench chat result discarded because conversation was stopped or deleted userId={} conversationId={}", user.getId(), conversationId);
                return response;
            }
            workspaceService.access(user, workspace.workspaceId());
            boolean recorded = conversationService.recordAssistantMessage(user, workspace.workspaceId(), clientConversationId,
                    mode, ragResponse.answer(), response.sources(), response.toolCalls());
            if (recorded) {
                // 只有助手消息成功持久化后才沉淀学习记录，避免收录已删除会话的迟到回答。
                    learningRecordService.record(user, workspace.workspaceId(), request.message(), ragResponse.answer(), ragResponse.sources());
            }
            return response;
        } finally {
            if (execution.isCancelled()) {
                // 取消后同步清理 JVM 内短期记忆，防止下次对话引用已删除上下文。
                conversationMemory.remove(conversationId);
            }
            executionRegistry.finish(conversationId, execution);
        }
    }

    private String newMessageId() {
        return "msg-" + UUID.randomUUID();
    }

    /**
     * 流式问答：RAG 检索后逐 token 输出回答，消息持久化和学习记录在回答输出完成后执行，
     * 使首字延迟不再被完整回答生成和数据库写操作阻塞。
     */
    public WorkbenchChatResponse streamChat(AppUser user, WorkbenchChatRequest request, Consumer<String> onToken) {
        long startedAt = System.currentTimeMillis();
        String mode = request.normalizedMode();
        String clientConversationId = request.normalizedConversationId();
        WorkspaceAccessContext workspace = workspaceService.access(user, request.workspaceId());
        String conversationId = conversationService.executionScope(user, workspace.workspaceId(), clientConversationId);

        ConversationExecutionRegistry.Execution execution = executionRegistry.begin(conversationId);
        try {
            conversationService.recordUserMessage(
                    user,
                    workspace.workspaceId(),
                    clientConversationId,
                    request.message().strip().substring(0, Math.min(request.message().strip().length(), 24)),
                    mode,
                    request.message()
            );

            log.info(
                    "Workbench stream chat received userId={} mode={} conversationId={} messageLength={}",
                    user.getId(),
                    mode,
                    conversationId,
                    request.message() == null ? 0 : request.message().length()
            );

            workspaceService.access(user, workspace.workspaceId());
            RagStreamResponse ragResponse = ragService.stream(user,
                    new RagChatRequest(conversationId, workspace.workspaceId(), clientConversationId, request.message()));

            StringBuilder content = new StringBuilder();
            AtomicBoolean firstTokenSent = new AtomicBoolean(false);
            ragResponse.tokens().doOnNext(token -> {
                        if (execution.isCancelled()) return;
                        if (token == null || token.isEmpty()) return;
                        if (firstTokenSent.compareAndSet(false, true)) {
                            log.info("Workbench stream chat first token forwarded conversationId={} latencyMs={}",
                                    conversationId, System.currentTimeMillis() - startedAt);
                        }
                        content.append(token);
                        onToken.accept(token);
                    })
                    .blockLast();

            if (execution.isCancelled()) {
                log.info("Workbench stream chat result discarded because conversation was stopped or deleted userId={} conversationId={}",
                        user.getId(), conversationId);
                return new WorkbenchChatResponse(newMessageId(), content.toString(), ragResponse.sources(), List.of());
            }

            workspaceService.access(user, workspace.workspaceId());
            String answerContent = ragService.sanitizePresentedAnswer(content.toString(), request.message());
            WorkbenchChatResponse response = new WorkbenchChatResponse(newMessageId(), answerContent, ragResponse.sources(), List.of());
            boolean recorded = conversationService.recordAssistantMessage(user, workspace.workspaceId(), clientConversationId,
                    mode, answerContent, response.sources(), response.toolCalls());
            if (recorded) {
                learningRecordService.record(user, workspace.workspaceId(), request.message(), answerContent, ragResponse.sources());
            }
            log.info(
                    "Workbench stream chat completed route=RAG_LOCAL_KNOWLEDGE conversationId={} sources={} durationMs={}",
                    conversationId,
                    ragResponse.sources().size(),
                    System.currentTimeMillis() - startedAt
            );
            return response;
        } finally {
            if (execution.isCancelled()) {
                conversationMemory.remove(conversationId);
            }
            executionRegistry.finish(conversationId, execution);
        }
    }
}
