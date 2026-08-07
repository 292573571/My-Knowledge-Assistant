package com.example.workbench.agent;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * 当前请求专属的只读 Agent 工具集合。
 *
 * <p>该对象不是单例工具 Bean，而是每次请求携带固定权限上下文创建，避免并发请求共享用户状态。</p>
 */
public class MaintenanceAgentTools {

    public static final int MAX_TOOL_CALLS = 5;

    private final MaintenanceReadOnlyService readOnlyService;
    private final MaintenanceAgentContext context;
    private final List<Invocation> invocations = new ArrayList<>();
    private int callCount;

    public MaintenanceAgentTools(MaintenanceReadOnlyService readOnlyService, MaintenanceAgentContext context) {
        this.readOnlyService = readOnlyService;
        this.context = context;
    }

    public synchronized List<Invocation> invocations() {
        return List.copyOf(invocations);
    }

    private synchronized <T> T invoke(String toolName, Supplier<T> operation) {
        if (++callCount > MAX_TOOL_CALLS) {
            invocations.add(new Invocation(toolName, "REJECTED"));
            throw new IllegalStateException("维护 Agent 已达到只读工具调用上限");
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

    @Tool(description = "查询当前授权知识空间的索引文档数量和分块数量，只能读取当前空间")
    public MaintenanceReadOnlyService.IndexStatusSummary getIndexStatus() {
        return invoke("getIndexStatus", () -> readOnlyService.indexStatus(context));
    }

    @Tool(description = "查询当前授权知识空间的文档处理任务。includeCompleted 为 true 时包含已完成任务")
    public MaintenanceReadOnlyService.TaskListSummary listDocumentTasks(
            @ToolParam(description = "是否包含已完成任务") boolean includeCompleted) {
        return invoke("listDocumentTasks", () -> readOnlyService.tasks(context, includeCompleted));
    }

    @Tool(description = "查询当前授权空间内指定文档任务的页面批次和失败原因")
    public MaintenanceReadOnlyService.BatchListSummary getDocumentTaskBatches(
            @ToolParam(description = "文档任务 ID") String taskId) {
        return invoke("getDocumentTaskBatches", () -> readOnlyService.batches(context, taskId));
    }

    @Tool(description = "查询当前授权空间的索引文档，只返回文件摘要，不返回正文")
    public MaintenanceReadOnlyService.DocumentListSummary listIndexedDocuments(
            @ToolParam(description = "按文件名或相对路径过滤，可为空") String keyword,
            @ToolParam(description = "返回数量，范围为 1 到 100") int limit) {
        return invoke("listIndexedDocuments", () -> readOnlyService.documents(context, keyword, limit));
    }

    public record Invocation(String toolName, String status) { }
}
