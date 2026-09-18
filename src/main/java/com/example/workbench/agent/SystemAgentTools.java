package com.example.workbench.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * 系统管家的工具集。
 *
 * <p>不是单例 Bean，而是每次请求携带固定权限上下文创建：模型无法通过参数篡改调用者身份或空间。
 * 所有工具都是只读的，写操作一律由 {@link SystemAgentService} 走待确认令牌执行。</p>
 */
public class SystemAgentTools {

    private static final int MAX_TOOL_CALLS = 10;

    private final MaintenanceReadOnlyService knowledgeReadOnly;
    private final SystemReadOnlyService systemReadOnly;
    private final MaintenanceAgentContext context;
    private final List<Invocation> invocations = new ArrayList<>();

    private int callCount;

    public SystemAgentTools(MaintenanceReadOnlyService knowledgeReadOnly, SystemReadOnlyService systemReadOnly,
                            MaintenanceAgentContext context) {
        this.knowledgeReadOnly = knowledgeReadOnly;
        this.systemReadOnly = systemReadOnly;
        this.context = context;
    }

    // ---------- 知识库与文档任务 ----------

    @Tool(description = "查询当前知识空间的索引文档数量和分块数量。需要登录并具备当前空间访问权限。")
    public MaintenanceReadOnlyService.IndexStatusSummary getIndexStatus() {
        return invoke("getIndexStatus", () -> knowledgeReadOnly.indexStatus(context));
    }

    @Tool(description = "查询当前知识空间的文档处理任务。includeCompleted 为 true 时包含已完成任务。")
    public MaintenanceReadOnlyService.TaskListSummary listDocumentTasks(
            @ToolParam(description = "是否包含已完成任务，默认 false", required = false) Boolean includeCompleted) {
        return invoke("listDocumentTasks",
                () -> knowledgeReadOnly.tasks(context, Boolean.TRUE.equals(includeCompleted)));
    }

    @Tool(description = "查询指定文档任务的页面批次与失败原因。")
    public MaintenanceReadOnlyService.BatchListSummary getDocumentTaskBatches(
            @ToolParam(description = "文档任务 ID", required = true) String taskId) {
        return invoke("getDocumentTaskBatches", () -> knowledgeReadOnly.batches(context, taskId));
    }

    @Tool(description = "列出当前知识空间的索引文档摘要（不含正文）。limit 范围 1 到 100。")
    public MaintenanceReadOnlyService.DocumentListSummary listIndexedDocuments(
            @ToolParam(description = "文件名或路径关键字，可为空", required = false) String keyword,
            @ToolParam(description = "返回条数 1-100，默认 20", required = false) Integer limit) {
        int safeLimit = limit == null ? 20 : Math.max(1, Math.min(100, limit));
        return invoke("listIndexedDocuments", () -> knowledgeReadOnly.documents(context, keyword, safeLimit));
    }

    // ---------- 全系统只读视图 ----------

    @Tool(description = "系统总览：用户总数与角色分布、知识空间总数、当前空间索引文档与分块数、失败/进行中任务数。需要系统管理员权限。")
    public SystemReadOnlyService.SystemOverview getSystemOverview() {
        return invoke("getSystemOverview", () -> systemReadOnly.overview(context));
    }

    @Tool(description = "查询用户目录（账号、名称、角色、脱敏邮箱）。可按关键字过滤，limit 1-50。需要系统管理员权限。")
    public SystemReadOnlyService.UserDirectory listUsers(
            @ToolParam(description = "账号、名称或公开 ID 关键字，可为空", required = false) String keyword,
            @ToolParam(description = "返回条数 1-50，默认 20", required = false) Integer limit) {
        int safeLimit = limit == null ? 20 : limit;
        return invoke("listUsers", () -> systemReadOnly.users(context, keyword, safeLimit));
    }

    @Tool(description = "查询模型池与默认模型（API Key 仅返回掩码）。需要系统管理员权限。")
    public SystemReadOnlyService.ModelDirectory listModels() {
        return invoke("listModels", () -> systemReadOnly.models(context));
    }

    @Tool(description = "查询最近的审计事件与失败动作统计。limit 1-50，默认 20。需要系统管理员权限。")
    public SystemReadOnlyService.AuditDirectory recentAuditEvents(
            @ToolParam(description = "返回条数 1-50，默认 20", required = false) Integer limit) {
        int safeLimit = limit == null ? 20 : limit;
        return invoke("recentAuditEvents", () -> systemReadOnly.auditEvents(context, safeLimit));
    }

    @Tool(description = "查询最近的系统日志摘要（正文已裁剪脱敏）。level 可选 ERROR/WARN/INFO，limit 1-50。需要系统管理员权限。")
    public SystemReadOnlyService.LogDirectory recentSystemLogs(
            @ToolParam(description = "日志级别，可为空表示全部", required = false) String level,
            @ToolParam(description = "返回条数 1-50，默认 20", required = false) Integer limit) {
        int safeLimit = limit == null ? 20 : limit;
        return invoke("recentSystemLogs", () -> systemReadOnly.systemLogs(context, level, safeLimit));
    }

    @Tool(description = "列出知识空间（含个人、团队、组织、公共）与层级关系。需要系统管理员权限。")
    public SystemReadOnlyService.WorkspaceDirectory listWorkspaces() {
        return invoke("listWorkspaces", () -> systemReadOnly.workspaces(context));
    }

    @Tool(description = "查询当前用户的评测运行记录与通过率。limit 1-50，默认 10。需要系统管理员权限。")
    public SystemReadOnlyService.EvalRunDirectory listEvalRuns(
            @ToolParam(description = "返回条数 1-50，默认 10", required = false) Integer limit) {
        int safeLimit = limit == null ? 10 : limit;
        return invoke("listEvalRuns", () -> systemReadOnly.evalRuns(context, safeLimit));
    }

    public List<Invocation> invocations() {
        return List.copyOf(invocations);
    }

    private <T> T invoke(String toolName, Supplier<T> operation) {
        if (++callCount > MAX_TOOL_CALLS) {
            invocations.add(new Invocation(toolName, "REJECTED"));
            throw new IllegalStateException("系统管家已达到只读工具调用上限");
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

    public record Invocation(String toolName, String status) {
    }
}
