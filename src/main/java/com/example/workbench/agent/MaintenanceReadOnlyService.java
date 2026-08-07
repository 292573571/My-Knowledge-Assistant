package com.example.workbench.agent;

import com.example.workbench.auth.AdminAuthorizationService;
import com.example.workbench.rag.DocumentIngestionService;
import com.example.workbench.rag.DocumentTaskBatchResponse;
import com.example.workbench.rag.DocumentTaskService;
import com.example.workbench.rag.DocumentTaskResponse;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

/**
 * 维护 Agent 的只读业务门面。
 *
 * <p>Agent 工具只能调用本类，不能直接访问仓储、文件系统或修改型服务。</p>
 */
@Service
public class MaintenanceReadOnlyService {

    private final DocumentIngestionService ingestionService;
    private final DocumentTaskService taskService;
    private final AdminAuthorizationService adminAuthorizationService;

    public MaintenanceReadOnlyService(DocumentIngestionService ingestionService, DocumentTaskService taskService,
                                       AdminAuthorizationService adminAuthorizationService) {
        this.ingestionService = ingestionService;
        this.taskService = taskService;
        this.adminAuthorizationService = adminAuthorizationService;
    }

    public IndexStatusSummary indexStatus(MaintenanceAgentContext context) {
        List<IndexedDocumentSummary> documents = documents(context, "", 100).documents();
        return new IndexStatusSummary(context.access().workspaceId(), documents.size(),
                documents.stream().mapToInt(IndexedDocumentSummary::chunkCount).sum());
    }

    public TaskListSummary tasks(MaintenanceAgentContext context, boolean includeCompleted) {
        List<MaintenanceTaskSummary> tasks = taskService.list(context.access(),
                        adminAuthorizationService.isAdmin(context.user())).stream()
                .filter(task -> includeCompleted || isActive(task.status().name()))
                .map(MaintenanceTaskSummary::from)
                .toList();
        return new TaskListSummary(tasks);
    }

    public BatchListSummary batches(MaintenanceAgentContext context, String taskId) {
        if (taskId == null || taskId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "taskId 不能为空");
        }
        List<DocumentTaskBatchResponse> batches = taskService.batches(taskId, context.access(),
                adminAuthorizationService.isAdmin(context.user()));
        return new BatchListSummary(taskId, batches);
    }

    public DocumentListSummary documents(MaintenanceAgentContext context, String keyword, int limit) {
        if (limit < 1 || limit > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "limit 必须在 1 到 100 之间");
        }
        String normalized = keyword == null ? "" : keyword.strip().toLowerCase();
        List<IndexedDocumentSummary> documents = ingestionService.listVisibleIndexedDocuments(context.access()).stream()
                .filter(document -> normalized.isBlank()
                        || document.fileName().toLowerCase().contains(normalized)
                        || document.path().toLowerCase().contains(normalized))
                .map(IndexedDocumentSummary::from)
                .limit(limit)
                .toList();
        return new DocumentListSummary(documents, documents.size());
    }

    private boolean isActive(String status) {
        return "QUEUED".equals(status) || "RUNNING".equals(status) || "RETRY_WAIT".equals(status);
    }

    public record IndexStatusSummary(String workspaceId, int documentCount, int chunkCount) { }
    public record TaskListSummary(List<MaintenanceTaskSummary> tasks) { }
    public record BatchListSummary(String taskId, List<DocumentTaskBatchResponse> batches) { }
    public record DocumentListSummary(List<IndexedDocumentSummary> documents, int total) { }
}
