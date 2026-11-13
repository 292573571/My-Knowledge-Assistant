package com.example.workbench.wiki;

import com.example.workbench.auth.AppUser;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.ai.chat.client.ChatClient;

/**
 * 文档 → wiki 笔记的 AI 沉淀任务。
 *
 * <p>LLM 生成耗时 10-30 秒，同步等待必然超时：受理后立即返回 taskId，
 * 后台取该文档切片拼接（上限 8000 字）交给对话模型提炼，前端轮询取草稿。
 * 任务仅存内存，草稿未落库前用户可放弃；已结束任务保留 30 分钟。
 */
@Service
public class WikiDraftTaskService {

    private static final Logger log = LoggerFactory.getLogger(WikiDraftTaskService.class);

    static final Duration FINISHED_TASK_RETENTION = Duration.ofMinutes(30);
    private static final int MAX_CONTEXT_CHARS = 8000;

    private final EntityManager entityManager;
    private final com.example.workbench.workspace.WorkspaceService workspaceService;
    private final ObjectProvider<ChatClient> chatClientProvider;
    private final ObjectMapper objectMapper;
    private final TaskExecutor ocrTaskExecutor;
    private final Map<String, Entry> tasks = new ConcurrentHashMap<>();

    private static final class Entry {
        private final Long userId;
        private final Instant createdAt;
        private volatile String status;
        private volatile String title;
        private volatile String contentMd;
        private volatile String error;

        private Entry(Long userId, Instant createdAt) {
            this.userId = userId;
            this.createdAt = createdAt;
            this.status = "RUNNING";
        }
    }

    public WikiDraftTaskService(EntityManager entityManager,
                                com.example.workbench.workspace.WorkspaceService workspaceService,
                                ObjectProvider<ChatClient> chatClientProvider,
                                ObjectMapper objectMapper,
                                @Qualifier("ocrTaskExecutor") TaskExecutor ocrTaskExecutor) {
        this.entityManager = entityManager;
        this.workspaceService = workspaceService;
        this.chatClientProvider = chatClientProvider;
        this.objectMapper = objectMapper;
        this.ocrTaskExecutor = ocrTaskExecutor;
    }

    public record DraftTaskView(String taskId, String status, String title, String contentMd, String error) { }

    /** 受理任务。工作空间访问校验失败直接抛出。 */
    public String submit(AppUser user, String workspaceId, String documentId) {
        if (documentId == null || documentId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "缺少 documentId");
        }
        // 提前校验空间访问权限，避免无权限用户排队占资源
        workspaceService.access(user, workspaceId);
        ChatClient chatClient = chatClientProvider.getIfAvailable();
        if (chatClient == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "当前没有可用的对话模型，无法生成笔记");
        }
        evictExpired();
        String taskId = UUID.randomUUID().toString();
        Entry entry = new Entry(user.getId(), Instant.now());
        tasks.put(taskId, entry);
        try {
            ocrTaskExecutor.execute(() -> run(taskId, entry, user, workspaceId, documentId, chatClient));
        } catch (RejectedExecutionException exception) {
            tasks.remove(taskId);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "生成任务队列已满，请稍后重试。");
        }
        return taskId;
    }

    /** 查询任务。任务不存在（重启/过期/非本人）返回 null。 */
    public DraftTaskView poll(AppUser user, String taskId) {
        Entry entry = tasks.get(taskId);
        if (entry == null || !entry.userId.equals(user.getId())) {
            return null;
        }
        return new DraftTaskView(taskId, entry.status, entry.title, entry.contentMd, entry.error);
    }

    private void run(String taskId, Entry entry, AppUser user, String workspaceId, String documentId, ChatClient chatClient) {
        try {
            String context = loadDocumentContext(user, workspaceId, documentId);
            if (context == null || context.isBlank()) {
                entry.status = "FAILED";
                entry.error = "没有找到可读的文档内容";
                return;
            }
            String output = chatClient.prompt().user(prompt(context)).call().content();
            Draft draft = objectMapper.readValue(cleanJson(output), Draft.class);
            if (draft == null || draft.title() == null || draft.title().isBlank()
                    || draft.contentMd() == null || draft.contentMd().isBlank()) {
                throw new IllegalStateException("模型返回内容不完整");
            }
            entry.title = draft.title().strip();
            entry.contentMd = draft.contentMd();
            entry.status = "SUCCEEDED";
            log.info("Wiki draft generated taskId={} documentId={} titleLength={} contentLength={}",
                    taskId, documentId, entry.title.length(), entry.contentMd.length());
        } catch (Exception exception) {
            entry.status = "FAILED";
            entry.error = exception.getClass().getSimpleName();
            log.warn("Wiki draft generation failed taskId={} documentId={} errorType={}",
                    taskId, documentId, exception.getClass().getSimpleName());
        }
    }

    /** 拉取文档全部切片（带可见性过滤），按序拼接并截断到上下文上限。 */
    private String loadDocumentContext(AppUser user, String workspaceId, String documentId) {
        var workspace = workspaceService.access(user, workspaceId);
        Set<String> readable = workspaceService.effectiveReadableWorkspaceIds(user, workspace.workspaceId());
        boolean scoped = readable != null && !readable.isEmpty();

        StringBuilder sql = new StringBuilder("""
                select c.title, c.heading_path, c.content, c.page_number, c.file_name
                from document_chunks c
                where c.document_id = :documentId and c.category <> 'LEARNING_RECORD' and (
                """);
        sql.append("c.visibility = 'PUBLIC'");
        if (workspace.userId() != null && !workspace.userId().isBlank()) {
            sql.append(" or (c.visibility = 'PRIVATE' and c.owner_user_id = :ownerUserId");
            if (scoped) {
                sql.append(" and c.workspace_id in (:workspaceIds)");
            }
            sql.append(")");
        }
        if (scoped) {
            sql.append(" or (c.visibility = 'WORKSPACE' and c.workspace_id in (:workspaceIds))");
        }
        sql.append(") order by c.chunk_index");

        var nativeQuery = entityManager.createNativeQuery(sql.toString());
        nativeQuery.setParameter("documentId", documentId);
        if (workspace.userId() != null && !workspace.userId().isBlank()) {
            nativeQuery.setParameter("ownerUserId", workspace.userId());
        }
        if (scoped) {
            nativeQuery.setParameter("workspaceIds", new ArrayList<>(readable));
        }
        @SuppressWarnings("unchecked")
        List<Object[]> rows = nativeQuery.getResultList();

        StringBuilder context = new StringBuilder();
        String fileName = null;
        for (Object[] row : rows) {
            String title = text(row[0]);
            String heading = text(row[1]);
            String content = text(row[2]);
            fileName = fileName == null ? text(row[4]) : fileName;
            if (content.isBlank()) continue;
            StringBuilder section = new StringBuilder();
            if (!heading.isBlank()) section.append("## ").append(heading).append('\n');
            section.append(content.strip()).append('\n');
            if (context.length() + section.length() > MAX_CONTEXT_CHARS) break;
            context.append(section);
        }
        if (context.isEmpty()) return fileName;
        return "文档名：" + (fileName == null ? "未知" : fileName) + "\n\n" + context;
    }

    private String prompt(String context) {
        return """
                你是学习笔记助手。下面是一份知识库文档的内容摘录，请把它提炼成一篇结构化学习笔记。

                要求：
                1. 忠于原文，不要编造文档里没有的事实。
                2. 用 Markdown 输出，依次包含四个二级标题章节：摘要、核心要点、术语卡、延伸问题。
                3. 「核心要点」用无序列表，每条一句话；「术语卡」用表格（术语 | 解释）；「延伸问题」列出 2-3 个值得继续探究的问题。
                4. 只输出 JSON，不要 Markdown 代码块包裹。contentMd 字段内部用 \\n 表示换行。

                输出格式：
                {"title": "笔记标题（12 字以内）", "contentMd": "完整 Markdown 笔记"}

                文档内容：
                %s
                """.formatted(context);
    }

    private String cleanJson(String output) {
        if (output == null) return "";
        String cleaned = output.trim();
        if (cleaned.startsWith("```json")) {
            cleaned = cleaned.substring("```json".length()).trim();
        } else if (cleaned.startsWith("```")) {
            cleaned = cleaned.substring("```".length()).trim();
        }
        if (cleaned.endsWith("```")) {
            cleaned = cleaned.substring(0, cleaned.length() - "```".length()).trim();
        }
        int start = cleaned.indexOf('{');
        int end = cleaned.lastIndexOf('}');
        return start >= 0 && end > start ? cleaned.substring(start, end + 1) : cleaned;
    }

    private record Draft(String title, String contentMd) { }

    private String text(Object value) {
        return value == null ? "" : value.toString().strip();
    }

    private void evictExpired() {
        Instant threshold = Instant.now().minus(FINISHED_TASK_RETENTION);
        tasks.entrySet().removeIf(item -> isFinished(item.getValue().status) && item.getValue().createdAt.isBefore(threshold));
    }

    private boolean isFinished(String status) {
        return "SUCCEEDED".equals(status) || "FAILED".equals(status);
    }
}
