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
import java.util.List;
import java.util.UUID;
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
}
