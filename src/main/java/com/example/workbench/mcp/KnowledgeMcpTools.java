package com.example.workbench.mcp;

import com.example.workbench.auth.AppUser;
import com.example.workbench.auth.UserConversationScope;
import com.example.workbench.rag.DocumentContentResponse;
import com.example.workbench.rag.DocumentIndexEntry;
import com.example.workbench.rag.DocumentIngestionService;
import com.example.workbench.rag.RagService;
import com.example.workbench.rag.RagSource;
import com.example.workbench.workspace.WorkspaceService;
import java.time.Instant;
import java.util.List;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/**
 * 暴露给 MCP 客户端的只读知识库工具。
 *
 * <p>当前用户来自 {@link McpRequestContext}（由 {@link McpAuthFilter} 在鉴权后设置），
 * 所有检索与文档读取都按该用户做数据隔离，不会跨用户串数据。
 */
@Component
@ConditionalOnProperty(prefix = "app.mcp", name = "enabled", havingValue = "true")
public class KnowledgeMcpTools {

    private final RagService ragService;
    private final DocumentIngestionService documentIngestionService;
    private final WorkspaceService workspaceService;

    /**
     * 三个依赖都懒加载：MCP 的 ToolCallbackProvider 会被 Spring AI 的 toolCallbackResolver 收集，
     * 而 resolver 又处在 openAiChatModel → chatClient 的创建链上。若此处直接注入 RagService，
     * 会形成 provider → tools → ragService → chatClient → resolver → provider 的循环导致启动失败。
     */
    public KnowledgeMcpTools(
            @Lazy RagService ragService,
            @Lazy DocumentIngestionService documentIngestionService,
            @Lazy WorkspaceService workspaceService
    ) {
        this.ragService = ragService;
        this.documentIngestionService = documentIngestionService;
        this.workspaceService = workspaceService;
    }

    @Tool(name = "search_knowledge",
            description = "在个人知识库中做语义检索，返回与问题最相关的片段及其来源文件。适合先检索再作答。")
    public List<KnowledgeHit> searchKnowledge(
            @ToolParam(description = "自然语言检索问题", required = true) String query,
            @ToolParam(description = "知识空间 ID，为空表示默认个人空间", required = false) String workspaceId,
            @ToolParam(description = "返回条数，1-10，默认 5", required = false) Integer limit
    ) {
        AppUser user = requireUser();
        int safeLimit = limit == null ? 5 : Math.max(1, Math.min(10, limit));
        return ragService
                .retrieveForAgent(query, UserConversationScope.ownerId(user), normalize(workspaceId), safeLimit)
                .stream()
                .map(KnowledgeHit::from)
                .toList();
    }

    @Tool(name = "list_documents",
            description = "列出当前用户可见的知识库文档，可按知识空间过滤。")
    public List<DocumentSummary> listDocuments(
            @ToolParam(description = "知识空间 ID，为空表示默认个人空间", required = false) String workspaceId
    ) {
        AppUser user = requireUser();
        return documentIngestionService
                .listWorkspaceIndexedDocuments(workspaceService.access(user, workspaceId)).stream()
                .map(DocumentSummary::from)
                .toList();
    }

    @Tool(name = "get_document",
            description = "按文档 ID 读取整篇文档的正文内容。先用 list_documents 拿到文档 ID。")
    public DocumentContentResponse getDocument(
            @ToolParam(description = "文档 ID", required = true) String documentId,
            @ToolParam(description = "知识空间 ID，为空表示默认个人空间", required = false) String workspaceId
    ) {
        AppUser user = requireUser();
        return documentIngestionService.documentContent(documentId, workspaceService.access(user, workspaceId));
    }

    private static AppUser requireUser() {
        AppUser user = McpRequestContext.currentUser();
        if (user == null) {
            throw new IllegalStateException("MCP 工具需要已认证用户，请检查 Authorization 头");
        }
        return user;
    }

    private static String normalize(String workspaceId) {
        return workspaceId == null || workspaceId.isBlank() ? null : workspaceId.strip();
    }

    public record KnowledgeHit(
            String file,
            String snippet,
            double score,
            String headingPath,
            String path
    ) {
        static KnowledgeHit from(RagSource source) {
            return new KnowledgeHit(source.file(), source.snippet(), source.score(),
                    source.headingPath(), source.path());
        }
    }

    public record DocumentSummary(
            String documentId,
            String fileName,
            String path,
            String category,
            int chunkCount,
            Instant ingestedAt
    ) {
        static DocumentSummary from(DocumentIndexEntry entry) {
            return new DocumentSummary(entry.documentId(), entry.fileName(), entry.path(),
                    entry.category(), entry.chunkCount(), entry.ingestedAt());
        }
    }
}
