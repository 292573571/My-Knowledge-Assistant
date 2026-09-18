package com.example.workbench.agent;

import com.example.workbench.auth.AdminAuthorizationService;
import com.example.workbench.auth.AdminUserResponse;
import com.example.workbench.auth.AdminUserService;
import com.example.workbench.audit.AuditEventResponse;
import com.example.workbench.audit.AuditService;
import com.example.workbench.eval.EvalRunEntity;
import com.example.workbench.eval.EvalRunStorage;
import com.example.workbench.logview.SystemLog;
import com.example.workbench.logview.SystemLogRepository;
import com.example.workbench.modelconfig.AiModelResponse;
import com.example.workbench.modelconfig.AiModelService;
import com.example.workbench.workspace.WorkspaceResponse;
import com.example.workbench.workspace.WorkspaceService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

/**
 * 系统管家的只读门面。
 *
 * <p>只暴露「窄化 + 脱敏」后的摘要：模型 API Key 只给掩码，用户邮箱只给掩码，
 * 系统日志正文裁剪并脱敏。绝不把 {@code AppUser}、{@code AiModel} 等含敏感字段的实体交给模型。</p>
 *
 * <p>所有方法都需要 ADMIN 及以上权限，权限在本类内校验，不依赖 Prompt 约束。</p>
 */
@Service
public class SystemReadOnlyService {

    private static final int MAX_LIMIT = 50;
    private static final int LOG_EXCERPT_LENGTH = 300;
    private static final Pattern SECRET_PATTERN = Pattern.compile(
            "(?i)(sk-[A-Za-z0-9_-]{6,}|bearer\\s+[A-Za-z0-9._-]{6,}|(?:password|passwd|pwd|api[-_]?key|secret|token)\\s*[=:]\\s*\\S+)");

    private final MaintenanceReadOnlyService knowledgeReadOnly;
    private final AdminUserService adminUserService;
    private final AiModelService aiModelService;
    private final AuditService auditService;
    private final SystemLogRepository systemLogRepository;
    private final WorkspaceService workspaceService;
    private final EvalRunStorage evalRunStorage;
    private final AdminAuthorizationService adminAuthorizationService;

    public SystemReadOnlyService(MaintenanceReadOnlyService knowledgeReadOnly,
                                 AdminUserService adminUserService,
                                 AiModelService aiModelService,
                                 AuditService auditService,
                                 SystemLogRepository systemLogRepository,
                                 WorkspaceService workspaceService,
                                 EvalRunStorage evalRunStorage,
                                 AdminAuthorizationService adminAuthorizationService) {
        this.knowledgeReadOnly = knowledgeReadOnly;
        this.adminUserService = adminUserService;
        this.aiModelService = aiModelService;
        this.auditService = auditService;
        this.systemLogRepository = systemLogRepository;
        this.workspaceService = workspaceService;
        this.evalRunStorage = evalRunStorage;
        this.adminAuthorizationService = adminAuthorizationService;
    }

    public SystemOverview overview(MaintenanceAgentContext context) {
        requireAdmin(context);
        List<AdminUserResponse> users = adminUserService.list(context.user());
        Map<String, Long> roleCounts = new LinkedHashMap<>();
        for (AdminUserResponse user : users) {
            roleCounts.merge(user.systemRole().name(), 1L, Long::sum);
        }
        List<WorkspaceResponse> workspaces = visibleWorkspaces(context);
        MaintenanceReadOnlyService.IndexStatusSummary knowledge = knowledgeReadOnly.indexStatus(context);
        List<MaintenanceTaskSummary> tasks = knowledgeReadOnly.tasks(context, true).tasks();
        long failed = tasks.stream().filter(task -> "FAILED".equals(task.status())).count();
        long running = tasks.stream()
                .filter(task -> "QUEUED".equals(task.status()) || "RUNNING".equals(task.status())
                        || "RETRY_WAIT".equals(task.status()))
                .count();
        return new SystemOverview(users.size(), roleCounts, workspaces.size(), knowledge.documentCount(),
                knowledge.chunkCount(), (int) failed, (int) running);
    }

    public UserDirectory users(MaintenanceAgentContext context, String keyword, int limit) {
        requireAdmin(context);
        int safeLimit = bound(limit);
        String needle = keyword == null ? "" : keyword.strip().toLowerCase(Locale.ROOT);
        List<UserSummary> matched = new ArrayList<>();
        for (AdminUserResponse user : adminUserService.list(context.user())) {
            String haystack = (user.account() + " " + user.userName() + " " + user.publicId()).toLowerCase(Locale.ROOT);
            if (!needle.isEmpty() && !haystack.contains(needle)) continue;
            matched.add(new UserSummary(user.publicId(), user.account(), user.userName(),
                    user.systemRole().name(), maskEmail(user.email()), user.createdAt()));
        }
        boolean truncated = matched.size() > safeLimit;
        return new UserDirectory(matched.stream().limit(safeLimit).toList(), truncated);
    }

    public ModelDirectory models(MaintenanceAgentContext context) {
        requireAdmin(context);
        List<ModelSummary> models = aiModelService.list(context.user()).stream()
                .map(model -> new ModelSummary(model.id(), model.name(), model.model(), model.modelType().name(),
                        model.enabled(), model.isDefault(), model.ownerPublicId() != null, model.apiKey()))
                .toList();
        String defaultChat = models.stream().filter(item -> item.isDefault() && "CHAT".equals(item.modelType()))
                .map(ModelSummary::name).findFirst().orElse(null);
        String defaultEmbedding = models.stream().filter(item -> item.isDefault() && "EMBEDDING".equals(item.modelType()))
                .map(ModelSummary::name).findFirst().orElse(null);
        return new ModelDirectory(models, defaultChat, defaultEmbedding);
    }

    public AuditDirectory auditEvents(MaintenanceAgentContext context, int limit) {
        requireAdmin(context);
        int safeLimit = bound(limit);
        List<AuditEventResponse> events = auditService.listAll();
        List<AuditItem> items = events.stream().limit(safeLimit)
                .map(event -> new AuditItem(event.createdAt(), event.actorPublicId(), event.action().name(),
                        event.outcome() == null ? null : event.outcome().name(), event.resourceType(),
                        event.resourceId(), event.reasonCode()))
                .toList();
        Map<String, Long> failureCounts = new LinkedHashMap<>();
        for (AuditEventResponse event : events) {
            if (event.outcome() != null && "FAILURE".equalsIgnoreCase(event.outcome().name())) {
                failureCounts.merge(event.action().name(), 1L, Long::sum);
            }
        }
        return new AuditDirectory(events.size(), items, failureCounts);
    }

    public LogDirectory systemLogs(MaintenanceAgentContext context, String level, int limit) {
        requireAdmin(context);
        int safeLimit = bound(limit);
        String normalizedLevel = level == null || level.isBlank() ? null : level.strip().toUpperCase(Locale.ROOT);
        List<SystemLog> logs = normalizedLevel == null
                ? systemLogRepository.findAllByOrderByTimestampDesc(PageRequest.of(0, safeLimit)).getContent()
                : systemLogRepository.findByLevelInOrderByTimestampDesc(List.of(normalizedLevel),
                        PageRequest.of(0, safeLimit)).getContent();
        List<LogItem> items = logs.stream().map(log -> new LogItem(log.getTimestamp(), log.getLevel(),
                log.getLogger(), log.getExceptionType(), excerpt(log.getMessage()))).toList();
        return new LogDirectory(normalizedLevel, items);
    }

    public WorkspaceDirectory workspaces(MaintenanceAgentContext context) {
        requireAdmin(context);
        List<WorkspaceResponse> visible = visibleWorkspaces(context);
        List<WorkspaceItem> items = visible.stream()
                .sorted(Comparator.comparing(WorkspaceResponse::id))
                .map(workspace -> new WorkspaceItem(workspace.id(), workspace.name(), workspace.type().name(),
                        workspace.parentId()))
                .toList();
        return new WorkspaceDirectory(items);
    }

    public EvalRunDirectory evalRuns(MaintenanceAgentContext context, int limit) {
        requireAdmin(context);
        int safeLimit = bound(limit);
        List<EvalRunEntity> runs = evalRunStorage.list(context.user());
        List<EvalRunItem> items = runs.stream().limit(safeLimit)
                .map(run -> new EvalRunItem(run.getRunId(), run.getTotal(), run.getPassed(), run.getFailed(),
                        run.getPassRate(), run.isGatePassed(), run.isEnhanced(), run.getCreatedAt()))
                .toList();
        return new EvalRunDirectory(runs.size(), items);
    }

    private List<WorkspaceResponse> visibleWorkspaces(MaintenanceAgentContext context) {
        return adminAuthorizationService.isSuperAdmin(context.user())
                ? workspaceService.listAll(context.user())
                : workspaceService.list(context.user());
    }

    private void requireAdmin(MaintenanceAgentContext context) {
        adminAuthorizationService.requireAdmin(context.user());
    }

    private static int bound(int limit) {
        if (limit <= 0) return 20;
        return Math.min(limit, MAX_LIMIT);
    }

    private static String maskEmail(String email) {
        if (email == null || email.isBlank()) return null;
        int at = email.indexOf('@');
        if (at <= 0) return "***";
        String local = email.substring(0, at);
        String domain = email.substring(at);
        return local.charAt(0) + "***" + domain;
    }

    private static String excerpt(String message) {
        if (message == null) return null;
        String redacted = SECRET_PATTERN.matcher(message).replaceAll("[REDACTED]");
        String flattened = redacted.replaceAll("\\s+", " ").strip();
        return flattened.length() <= LOG_EXCERPT_LENGTH
                ? flattened
                : flattened.substring(0, LOG_EXCERPT_LENGTH) + "…";
    }

    public record SystemOverview(int userCount, Map<String, Long> roleCounts, int workspaceCount,
                                 int documentCount, int chunkCount, int failedTaskCount, int runningTaskCount) {
    }

    public record UserSummary(String publicId, String account, String userName, String systemRole,
                              String maskedEmail, Instant createdAt) {
    }

    public record UserDirectory(List<UserSummary> users, boolean truncated) {
    }

    public record ModelSummary(Long id, String name, String model, String modelType, boolean enabled,
                               boolean isDefault, boolean personal, String maskedApiKey) {
    }

    public record ModelDirectory(List<ModelSummary> models, String defaultChatModel, String defaultEmbeddingModel) {
    }

    public record AuditItem(Instant at, String actorPublicId, String action, String outcome, String resourceType,
                            String resourceId, String reasonCode) {
    }

    public record AuditDirectory(int total, List<AuditItem> recent, Map<String, Long> failureCounts) {
    }

    public record LogItem(Instant at, String level, String logger, String exceptionType, String messageExcerpt) {
    }

    public record LogDirectory(String level, List<LogItem> logs) {
    }

    public record WorkspaceItem(String id, String name, String type, String parentId) {
    }

    public record WorkspaceDirectory(List<WorkspaceItem> workspaces) {
    }

    public record EvalRunItem(String runId, int total, int passed, int failed, double passRate,
                              Boolean gatePassed, boolean enhanced, Instant createdAt) {
    }

    public record EvalRunDirectory(int total, List<EvalRunItem> runs) {
    }
}
