package com.example.workbench.agent;

import com.example.workbench.auth.UserConversationScope;
import com.example.workbench.rag.RagService;
import com.example.workbench.rag.RagSource;
import com.example.workbench.workspace.WorkspaceService;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * 面向普通用户的使用客服工具集（全部只读）。
 *
 * <p>只能检索公共知识空间里的帮助文档，以及查看调用者自己的文档任务状态，
 * 不涉及任何写操作，也不会触达他人的知识空间。</p>
 */
public class SupportAgentTools {

    private static final int MAX_TOOL_CALLS = 4;

    private final RagService ragService;
    private final WorkspaceService workspaceService;
    private final MaintenanceReadOnlyService knowledgeReadOnly;
    private final MaintenanceAgentContext context;
    private final List<Invocation> invocations = new ArrayList<>();

    private int callCount;

    public SupportAgentTools(RagService ragService, WorkspaceService workspaceService,
                             MaintenanceReadOnlyService knowledgeReadOnly, MaintenanceAgentContext context) {
        this.ragService = ragService;
        this.workspaceService = workspaceService;
        this.knowledgeReadOnly = knowledgeReadOnly;
        this.context = context;
    }

    @Tool(description = "在系统帮助文档（公共知识空间）中检索与问题最相关的片段。回答系统使用问题前应先调用。limit 范围 1 到 5。")
    public List<HelpHit> searchHelpDocs(
            @ToolParam(description = "自然语言问题", required = true) String query,
            @ToolParam(description = "返回条数 1-5，默认 3", required = false) Integer limit) {
        int safeLimit = limit == null ? 3 : Math.max(1, Math.min(5, limit));
        return invoke("searchHelpDocs", () -> {
            Set<String> publicWorkspaceIds = workspaceService.publicWorkspaceIds();
            if (publicWorkspaceIds.isEmpty()) {
                return List.<HelpHit>of();
            }
            return ragService.retrieveForAgent(query, UserConversationScope.ownerId(context.user()),
                            publicWorkspaceIds, safeLimit).stream()
                    .map(HelpHit::from)
                    .toList();
        });
    }

    @Tool(description = "查询当前用户自己知识空间里失败或进行中的文档处理任务，用于解释上传/解析为什么没成功。")
    public MaintenanceReadOnlyService.TaskListSummary getMyJobStatus() {
        return invoke("getMyJobStatus", () -> knowledgeReadOnly.tasks(context, false));
    }

    public List<Invocation> invocations() {
        return List.copyOf(invocations);
    }

    private <T> T invoke(String toolName, Supplier<T> operation) {
        if (++callCount > MAX_TOOL_CALLS) {
            invocations.add(new Invocation(toolName, "REJECTED"));
            throw new IllegalStateException("客服助手已达到只读工具调用上限");
        }
        try {
            T result = operation.get();
            invocations.add(new Invocation(toolName, "SUCCEEDED"));
            return result;
        } catch (RuntimeException exception) {
            invocations.add(new Invocation(toolName, "FAILED"));
            throw exception;
        }
    }

    /** 只返回文件名与片段，不暴露内部存储路径。 */
    public record HelpHit(String file, String snippet, String headingPath) {
        static HelpHit from(RagSource source) {
            return new HelpHit(source.file(), source.snippet(), source.headingPath());
        }
    }

    public record Invocation(String toolName, String status) {
    }
}
