package com.example.workbench.rag;

import com.example.workbench.auth.AdminAuthorizationService;
import com.example.workbench.auth.AppUser;
import com.example.workbench.auth.AppUserRepository;
import com.example.workbench.workspace.WorkspaceAccessContext;
import com.example.workbench.workspace.WorkspaceService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Service
public class DocumentTaskService {

    private static final Logger log = LoggerFactory.getLogger(DocumentTaskService.class);
    private static final long LEASE_SECONDS = 600;
    private final DocumentTaskRepository repository;
    private final DocumentIngestionService ingestionService;
    private final Executor executor;
    private final AppUserRepository userRepository;
    private final WorkspaceService workspaceService;
    private final AdminAuthorizationService adminAuthorizationService;
    private final String workerId = UUID.randomUUID().toString();

    public DocumentTaskService(
            DocumentTaskRepository repository,
            DocumentIngestionService ingestionService,
            @Qualifier("applicationTaskExecutor") Executor executor,
            AppUserRepository userRepository,
            WorkspaceService workspaceService,
            AdminAuthorizationService adminAuthorizationService
    ) {
        this.repository = repository;
        this.ingestionService = ingestionService;
        this.executor = executor;
        this.userRepository = userRepository;
        this.workspaceService = workspaceService;
        this.adminAuthorizationService = adminAuthorizationService;
    }

    /**
     * 创建上传任务并立即提交后台执行。
     *
     * @param access 空间访问上下文
     * @param file 上传文件
     * @return 新建任务
     */
    public DocumentTaskResponse createUpload(WorkspaceAccessContext access, MultipartFile file, String clientRequestId) {
        if (clientRequestId == null || clientRequestId.isBlank() || clientRequestId.length() > 64) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, "上传请求标识无效");
        }
        DocumentTaskEntity existing = repository.findByClientRequestIdAndActorUserIdAndWorkspaceId(
                clientRequestId, access.userId(), access.workspaceId()).orElse(null);
        if (existing != null) {
            return DocumentTaskResponse.from(existing);
        }
        DocumentIngestionService.PendingWorkspaceUpload upload = ingestionService.saveWorkspaceUpload(access, file);
        String taskId = UUID.randomUUID().toString();
        try {
            DocumentTaskEntity task = repository.saveAndFlush(new DocumentTaskEntity(
                    taskId, upload.fileName(), upload.sourcePath(), clientRequestId, access.userId(), access.workspaceId(),
                    access.role(), access.type()));
            submit(taskId);
            return DocumentTaskResponse.from(task);
        } catch (RuntimeException exception) {
            deleteSource(upload.sourcePath());
            throw exception;
        }
    }

    /**
     * 创建空间维护任务。
     *
     * @param access 空间访问上下文
     * @param type 维护任务类型
     * @param sourcePath 可选的服务器资料路径
     * @return 新建任务
     */
    public DocumentTaskResponse createMaintenance(WorkspaceAccessContext access, DocumentTaskType type, String sourcePath) {
        if (!access.canWrite()) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN, "当前角色不能维护空间文档");
        }
        if (type == DocumentTaskType.UPLOAD) {
            throw new IllegalArgumentException("UPLOAD must include a file");
        }
        String label = switch (type) {
            case INGEST_FILE -> "导入文件：" + sourcePath;
            case INGEST_DIRECTORY -> "导入目录：" + (sourcePath == null || sourcePath.isBlank() ? "空间目录" : sourcePath);
            case SYNC -> "同步空间文档";
            case REBUILD -> "重建空间索引";
            default -> type.name();
        };
        DocumentTaskEntity task = repository.saveAndFlush(new DocumentTaskEntity(UUID.randomUUID().toString(),
                type, label, sourcePath, access.userId(), access.workspaceId(), access.role(), access.type()));
        submit(task.getTaskId());
        return DocumentTaskResponse.from(task);
    }

    /**
     * 查询当前空间的文档任务。
     *
     * @param access 空间访问上下文
     * @return 按创建时间倒序排列的任务
     */
    public List<DocumentTaskResponse> list(WorkspaceAccessContext access, boolean systemAdmin) {
        return repository.findTop20ByWorkspaceIdOrderByCreatedAtDesc(access.workspaceId()).stream()
                .filter(task -> systemAdmin || task.getType() == DocumentTaskType.UPLOAD)
                .map(task -> DocumentTaskResponse.from(task, documentDeleted(task, access)))
                .toList();
    }

    /**
     * 重新执行失败任务。
     *
     * @param taskId 任务标识
     * @param access 空间访问上下文
     * @return 重新排队后的任务
     */
    public DocumentTaskResponse retry(String taskId, WorkspaceAccessContext access, boolean systemAdmin) {
        if (!access.canWrite()) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN, "当前角色不能重试文档任务");
        }
        DocumentTaskEntity task = visibleTask(taskId, access);
        if (task.getType() != DocumentTaskType.UPLOAD && !systemAdmin) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN, "维护任务仅限系统管理员重试");
        }
        if (task.getStatus() != DocumentTaskStatus.FAILED) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT, "仅失败任务可以重试");
        }
        task.retry();
        DocumentTaskEntity saved = repository.saveAndFlush(task);
        submit(taskId);
        return DocumentTaskResponse.from(saved);
    }

    /**
     * 读取上传任务对应的原始文件。
     *
     * @param taskId 任务标识
     * @param access 空间访问上下文
     * @return 原始文件
     */
    public DocumentSourceFile sourceFile(String taskId, WorkspaceAccessContext access) {
        DocumentTaskEntity task = visibleTask(taskId, access);
        if (task.getType() != DocumentTaskType.UPLOAD) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "上传记录不存在");
        }
        if (documentDeleted(task, access)) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.GONE, "知识库文档已删除，源文件不可打开");
        }
        try {
            return ingestionService.sourceFile(task.getSourcePath(), task.getFileName());
        } catch (ResponseStatusException exception) {
            if (exception.getStatusCode().value() != 404 || task.getDocumentId() == null) {
                throw exception;
            }
            return ingestionService.sourceFile(task.getDocumentId(), task.getFileName(), access);
        }
    }

    @Scheduled(fixedDelayString = "${workbench.document-tasks.poll-interval-ms:5000}")
    void pollTasks() {
        renewOwnedLeases();
        recoverExpiredTasks();
        repository.findByStatusInAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
                        List.of(DocumentTaskStatus.QUEUED, DocumentTaskStatus.RETRY_WAIT), Instant.now())
                .stream()
                .limit(2)
                .forEach(task -> submit(task.getTaskId()));
    }

    void process(String taskId) {
        Instant now = Instant.now();
        int claimed;
        try {
            claimed = repository.claim(taskId, DocumentTaskStatus.QUEUED, DocumentTaskStatus.RETRY_WAIT,
                    DocumentTaskStatus.RUNNING, now, workerId, now.plusSeconds(LEASE_SECONDS));
        } catch (org.springframework.dao.DataIntegrityViolationException exception) {
            // 同空间已有运行任务，保留队列状态并由后续轮询再次尝试。
            return;
        }
        if (claimed == 0) {
            return;
        }
        DocumentTaskEntity task = repository.findById(taskId).orElseThrow();

        try {
            WorkspaceAccessContext access = resolveCurrentAccess(task);
            String documentId = executeTask(task, access);
            task = repository.findById(taskId).orElseThrow();
            task.succeed(documentId);
            repository.saveAndFlush(task);
            log.info("Document task completed taskId={} type={} documentId={}", taskId, task.getType(), documentId);
        } catch (Exception exception) {
            task = repository.findById(taskId).orElseThrow();
            String failedStage = task.getStage();
            task.fail(publicErrorMessage(exception), isRetryable(exception));
            repository.saveAndFlush(task);
            // 后台线程没有 HTTP 异常处理器兜底，必须保留完整异常链才能区分 OCR、Embedding 和 Chroma 故障。
            log.warn("Document task failed taskId={} type={} fileName={} failedStage={} status={} attempt={}",
                    taskId, task.getType(), task.getFileName(), failedStage, task.getStatus(),
                    task.getAttemptCount(), exception);
        }
    }

    private void submit(String taskId) {
        try {
            executor.execute(() -> process(taskId));
        } catch (RuntimeException exception) {
            // 任务已经持久化，线程池临时不可用时由定时轮询继续执行。
            log.warn("Document task immediate submission failed taskId={} errorType={}", taskId,
                    exception.getClass().getSimpleName());
        }
    }

    private String executeTask(DocumentTaskEntity task, WorkspaceAccessContext access) throws java.io.IOException {
        return switch (task.getType()) {
            case UPLOAD -> ingestionService.indexWorkspaceUpload(
                    access, task.getSourcePath(), task.getFileName(),
                    (stage, progress) -> updateProgress(task.getTaskId(), stage, progress)).documentId();
            case INGEST_FILE -> {
                updateProgress(task.getTaskId(), "PARSING", 25);
                IngestResponse response = ingestionService.ingestDocument(task.getSourcePath(), true, access);
                requireSuccessfulIngest(response, "文件导入");
                updateProgress(task.getTaskId(), "PERSISTING_INDEX", 90);
                yield response.documents().stream().map(IngestDocumentResult::documentId)
                        .filter(id -> id != null && !id.isBlank()).findFirst().orElse(null);
            }
            case INGEST_DIRECTORY -> {
                updateProgress(task.getTaskId(), "SCANNING", 20);
                IngestResponse response = ingestionService.ingestDirectory(task.getSourcePath(), true, access);
                requireSuccessfulIngest(response, "目录导入");
                updateProgress(task.getTaskId(), "PERSISTING_INDEX", 90);
                yield null;
            }
            case SYNC -> {
                updateProgress(task.getTaskId(), "SCANNING", 20);
                ingestionService.syncWorkspace(access);
                updateProgress(task.getTaskId(), "PERSISTING_INDEX", 90);
                yield null;
            }
            case REBUILD -> {
                updateProgress(task.getTaskId(), "SCANNING", 15);
                ingestionService.rebuildDocuments(access);
                updateProgress(task.getTaskId(), "PERSISTING_INDEX", 90);
                yield null;
            }
        };
    }

    WorkspaceAccessContext resolveCurrentAccess(DocumentTaskEntity task) {
        AppUser actor;
        try {
            actor = userRepository.findById(Long.parseLong(task.getActorUserId())).orElseThrow();
        } catch (RuntimeException exception) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN, "任务创建者已不存在");
        }
        WorkspaceAccessContext access = workspaceService.access(actor, task.getWorkspaceId());
        if (!access.canWrite()) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN, "任务创建者已失去空间写权限");
        }
        if (task.getType() != DocumentTaskType.UPLOAD) {
            adminAuthorizationService.requireAdmin(actor);
        }
        return access;
    }

    private void requireSuccessfulIngest(IngestResponse response, String operation) {
        if (response.failed() > 0) {
            String reason = response.documents().stream()
                    .filter(item -> "failed".equals(item.status()))
                    .map(IngestDocumentResult::reason)
                    .filter(value -> value != null && !value.isBlank())
                    .findFirst()
                    .orElse("存在无法处理的文档");
            throw new IllegalArgumentException(operation + "失败：" + reason);
        }
    }

    private void updateProgress(String taskId, String stage, int progress) {
        repository.findById(taskId).ifPresent(task -> {
            if (!workerId.equals(task.getWorkerId())) {
                return;
            }
            task.updateProgress(stage, progress);
            task.renewLease(workerId, Instant.now().plusSeconds(LEASE_SECONDS));
            repository.saveAndFlush(task);
        });
    }

    private void recoverExpiredTasks() {
        repository.findByStatusAndLeaseExpiresAtLessThanEqual(DocumentTaskStatus.RUNNING, Instant.now())
                .forEach(task -> {
                    task.recoverInterruptedExecution();
                    repository.saveAndFlush(task);
                });
    }

    private void renewOwnedLeases() {
        Instant expiresAt = Instant.now().plusSeconds(LEASE_SECONDS);
        repository.findByStatusAndWorkerId(DocumentTaskStatus.RUNNING, workerId).forEach(task -> {
            task.renewLease(workerId, expiresAt);
            repository.saveAndFlush(task);
        });
    }

    @Scheduled(cron = "${workbench.document-tasks.cleanup-cron:0 20 3 * * *}")
    void cleanupExpiredFailedUploads() {
        repository.findByTypeAndStatusAndFinishedAtLessThanEqual(
                        DocumentTaskType.UPLOAD, DocumentTaskStatus.FAILED, Instant.now().minusSeconds(7L * 24 * 60 * 60))
                .forEach(task -> {
                    deleteSource(task.getSourcePath());
                    repository.delete(task);
                });
    }

    private DocumentTaskEntity visibleTask(String taskId, WorkspaceAccessContext access) {
        return repository.findById(taskId)
                .filter(task -> task.getWorkspaceId().equals(access.workspaceId()))
                .orElseThrow(() -> new ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "文档任务不存在"));
    }

    private boolean documentDeleted(DocumentTaskEntity task, WorkspaceAccessContext access) {
        return task.getType() == DocumentTaskType.UPLOAD
                && task.getStatus() == DocumentTaskStatus.SUCCEEDED
                && task.getDocumentId() != null
                && !ingestionService.isIndexedDocumentAvailable(task.getDocumentId(), access);
    }

    private boolean isRetryable(Exception exception) {
        return !(exception instanceof IllegalArgumentException) && !(exception instanceof ResponseStatusException);
    }

    private String publicErrorMessage(Exception error) {
        if (error instanceof ResponseStatusException responseStatusException) {
            return responseStatusException.getReason() == null ? "文档处理失败" : responseStatusException.getReason();
        }
        if (error instanceof IllegalArgumentException && error.getMessage() != null && !error.getMessage().isBlank()) {
            return error.getMessage().replaceAll("(?:[A-Za-z]:)?[/\\\\][^\\s，。；]+", "[路径已隐藏]");
        }
        return isRetryable(error) ? "临时服务不可用，系统将自动重试" : "文档处理失败";
    }

    private void deleteSource(String sourcePath) {
        try {
            Files.deleteIfExists(Path.of(sourcePath).toAbsolutePath().normalize());
        } catch (Exception ignored) {
            // 数据库创建失败时尽力清理源文件，原始异常仍交给调用方。
        }
    }
}
