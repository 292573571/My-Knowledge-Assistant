package com.example.workbench.wiki;

import com.example.workbench.auth.AppUser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * Wiki 导入的异步任务登记表。
 *
 * <p>大文件（扫描版 PDF / 纯图片 PPT）解析与 OCR 可能耗时数分钟，同步等待必然超时。
 * 提交时把上传内容拷贝到独立临时文件（Servlet 容器会在请求结束后清理上传临时文件），
 * 交给 {@code ocrTaskExecutor} 后台解析，前端轮询任务状态直到出结果。
 *
 * <p>任务仅保存在内存中：草稿解析是可重试的前置步骤，重启后任务丢失由前端
 * 以「任务不存在，请重新导入」提示兜底；已结束任务保留 30 分钟供查询，随后清理。
 */
@Service
public class WikiImportTaskService {

    private static final Logger log = LoggerFactory.getLogger(WikiImportTaskService.class);

    static final int MAX_TRACKED_TASKS = 200;
    static final Duration FINISHED_TASK_RETENTION = Duration.ofMinutes(30);

    private final WikiPageService wikiPageService;
    private final org.springframework.core.task.TaskExecutor ocrTaskExecutor;
    private final Map<String, Entry> tasks = new ConcurrentHashMap<>();

    /** 任务条目：status/content/error 会被后台线程与轮询线程并发读写，用 volatile 保证可见性。 */
    private static final class Entry {
        private final Long userId;
        private final String fileName;
        private final Instant createdAt;
        private volatile String status;
        private volatile WikiImportContent content;
        private volatile String error;

        private Entry(Long userId, String fileName, Instant createdAt, String status) {
            this.userId = userId;
            this.fileName = fileName;
            this.createdAt = createdAt;
            this.status = status;
        }
    }

    public WikiImportTaskService(
            WikiPageService wikiPageService,
            @Qualifier("ocrTaskExecutor") org.springframework.core.task.TaskExecutor ocrTaskExecutor) {
        this.wikiPageService = wikiPageService;
        this.ocrTaskExecutor = ocrTaskExecutor;
    }

    /**
     * 受理导入任务，立即返回任务 ID。上传内容拷贝到临时文件后交由后台解析，
     * 受理失败（文件保存失败 / 队列饱和）直接抛出，由控制器转成 4xx/5xx。
     */
    public String submit(AppUser user, String workspaceId, MultipartFile file) throws IOException {
        evictExpired();
        String taskId = UUID.randomUUID().toString();
        Path staged = stage(file);
        Entry entry = new Entry(
                user.getId(),
                file.getOriginalFilename(),
                Instant.now(),
                "RUNNING");
        tasks.put(taskId, entry);
        try {
            ocrTaskExecutor.execute(() -> run(taskId, entry, workspaceId, staged));
        } catch (RejectedExecutionException exception) {
            tasks.remove(taskId);
            deleteQuietly(staged);
            throw new IllegalStateException("导入任务队列已满，请稍后重试。", exception);
        }
        return taskId;
    }

    /** 查询任务状态。任务不存在（重启/过期）返回 null；非本用户的任务同样视为不存在。 */
    public WikiImportTaskResponse poll(AppUser user, String taskId) {
        Entry entry = tasks.get(taskId);
        if (entry == null || !java.util.Objects.equals(entry.userId, user.getId())) {
            return null;
        }
        return switch (entry.status) {
            case "SUCCEEDED" -> WikiImportTaskResponse.succeeded(taskId, entry.content);
            case "FAILED" -> WikiImportTaskResponse.failed(taskId, entry.fileName, entry.error);
            default -> WikiImportTaskResponse.running(taskId);
        };
    }

    private void run(String taskId, Entry entry, String workspaceId, Path staged) {
        try {
            MultipartFile wrapped = new TempFileMultipartFile(
                    staged, entry.fileName, Files.probeContentType(staged));
            WikiImportContent content = wikiPageService.importFromFile(null, workspaceId, wrapped);
            entry.content = content;
            entry.status = "SUCCEEDED";
        } catch (Exception exception) {
            log.warn("Wiki import task failed taskId={} fileName={} errorType={}",
                    taskId, entry.fileName, exception.getClass().getSimpleName());
            entry.error = exception.getMessage() == null || exception.getMessage().isBlank()
                    ? "文件解析失败，请确认格式受支持后重试。"
                    : exception.getMessage();
            entry.status = "FAILED";
        } finally {
            deleteQuietly(staged);
        }
    }

    /** 把上传内容拷到独立临时文件，规避容器在请求结束后清理上传临时文件的问题。 */
    private Path stage(MultipartFile file) throws IOException {
        String original = file.getOriginalFilename();
        String suffix = "";
        if (original != null && original.lastIndexOf('.') >= 0) {
            String candidate = original.substring(original.lastIndexOf('.'));
            if (candidate.length() <= 10 && candidate.matches("\\.[A-Za-z0-9]+")) {
                suffix = candidate;
            }
        }
        Path staged = Files.createTempFile("wiki-import-", suffix);
        try (var input = file.getInputStream()) {
            Files.copy(input, staged, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        return staged;
    }

    private void evictExpired() {
        Instant cutoff = Instant.now().minus(FINISHED_TASK_RETENTION);
        tasks.values().removeIf(entry ->
                !"RUNNING".equals(entry.status) && entry.createdAt.isBefore(cutoff));
        int overflow = tasks.size() - MAX_TRACKED_TASKS;
        if (overflow > 0) {
            tasks.entrySet().stream()
                    .filter(item -> !"RUNNING".equals(item.getValue().status))
                    .sorted(Comparator.comparing(item -> item.getValue().createdAt))
                    .limit(overflow)
                    .map(Map.Entry::getKey)
                    .forEach(tasks::remove);
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException exception) {
            log.debug("Wiki import staged file cleanup failed path={}", path);
        }
    }
}
