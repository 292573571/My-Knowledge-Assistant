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
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.UUID;
import com.example.workbench.pagination.PageResponse;
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
    private DocumentTaskBatchRepository batchRepository;
    private DocumentTaskBatchArtifactStore batchArtifactStore;

    /**
     * 注入可选的文档任务指标记录器，单元测试直接构造服务时可以不提供。
     *
     * @param metrics RAG 指标记录器
     */
    /**
     * 注入长文档批次记录仓库。
     *
     * @param batchRepository 批次记录仓库
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setBatchRepository(DocumentTaskBatchRepository batchRepository) {
        this.batchRepository = batchRepository;
    }

    /**
     * 注入批次产物存储，用于失败时移除不可恢复产物。
     *
     * @param batchArtifactStore 批次产物存储
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setBatchArtifactStore(DocumentTaskBatchArtifactStore batchArtifactStore) {
        this.batchArtifactStore = batchArtifactStore;
    }

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

    public PageResponse<DocumentTaskResponse> page(WorkspaceAccessContext access, boolean systemAdmin, int page, int size) {
        int safePage = Math.max(0, page);
        int safeSize = Math.min(Math.max(1, size), 500);
        org.springframework.data.domain.Page<DocumentTaskEntity> result = systemAdmin
                ? repository.findByWorkspaceId(access.workspaceId(),
                org.springframework.data.domain.PageRequest.of(safePage, safeSize,
                        org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC, "createdAt")
                                .and(org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC, "taskId"))))
                : repository.findByWorkspaceIdAndType(access.workspaceId(), DocumentTaskType.UPLOAD,
                org.springframework.data.domain.PageRequest.of(safePage, safeSize,
                        org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC, "createdAt")
                                .and(org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC, "taskId"))));
        return new PageResponse<>(result.getContent().stream()
                .map(task -> DocumentTaskResponse.from(task, documentDeleted(task, access))).toList(),
                result.getNumber(), result.getSize(), result.getTotalElements(), result.getTotalPages(), result.hasNext());
    }

    /**
     * 查询一个可见任务的全部页面批次。
     *
     * @param taskId 任务标识
     * @param access 空间访问上下文
     * @param systemAdmin 当前用户是否为系统管理员
     * @return 按序排列的批次
     */
    public List<DocumentTaskBatchResponse> batches(String taskId, WorkspaceAccessContext access,
                                                    boolean systemAdmin) {
        DocumentTaskEntity task = visibleTask(taskId, access);
        if (task.getType() != DocumentTaskType.UPLOAD && !systemAdmin) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN, "维护任务批次仅限系统管理员查看");
        }
        if (batchRepository == null) return List.of();
        return batchRepository.findByTaskIdOrderByBatchIndex(taskId).stream()
                .map(DocumentTaskBatchResponse::from).toList();
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
        if (!task.isRetryable()) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,
                    "该失败由文件内容或资源限制导致，请处理原文件后重新上传");
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
        if (task.getStatus() != DocumentTaskStatus.SUCCEEDED
                && !access.canWrite()
                && !task.getActorUserId().equals(access.userId())) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN,
                    "处理中或失败的原始上传仅限上传者和空间维护者查看");
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
         long generation = task.getGeneration();

        try {
            WorkspaceAccessContext access = resolveCurrentAccess(task);
             String documentId = executeTask(task, access, generation);
             int updated = repository.succeed(taskId, DocumentTaskStatus.RUNNING, DocumentTaskStatus.SUCCEEDED,
                     workerId, generation, documentId, Instant.now());
             if (updated == 1) {
                 deleteBatchArtifacts(taskId);
                 log.info("Document task completed taskId={} type={} documentId={}", taskId, task.getType(), documentId);
             }
         } catch (Exception exception) {
             String failedStage = task.getStage();
             boolean retryable = isRetryable(exception);
             DocumentTaskStatus nextStatus = retryable && task.getAttemptCount() < task.getMaxAttempts()
                     ? DocumentTaskStatus.RETRY_WAIT : DocumentTaskStatus.FAILED;
             Instant nextAttemptAt = nextStatus == DocumentTaskStatus.RETRY_WAIT
                     ? Instant.now().plusSeconds(30L * task.getAttemptCount()) : null;
             int updated = repository.fail(taskId, DocumentTaskStatus.RUNNING, workerId, generation,
                     publicErrorMessage(exception), retryable, nextStatus, nextStatus == DocumentTaskStatus.RETRY_WAIT
                             ? "RETRY_WAIT" : "FAILED", nextAttemptAt,
                     nextStatus == DocumentTaskStatus.FAILED ? Instant.now() : null);
             if (updated == 1) {
                 failCurrentBatch(task, publicErrorMessage(exception));
                 if (nextStatus == DocumentTaskStatus.FAILED) deleteBatchArtifacts(taskId);
             }
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

     private String executeTask(DocumentTaskEntity task, WorkspaceAccessContext access, long generation) throws java.io.IOException {
        return switch (task.getType()) {
            case UPLOAD -> ingestionService.indexWorkspaceUpload(
                    access, task.getSourcePath(), task.getFileName(), task.getTaskId(),
                     (stage, progress) -> updateProgress(task.getTaskId(), generation, stage, progress)).documentId();
            case INGEST_FILE -> {
                 updateProgress(task.getTaskId(), generation, "PARSING", 25);
                IngestResponse response = ingestionService.ingestDocument(task.getSourcePath(), true, access);
                requireSuccessfulIngest(response, "文件导入");
                 updateProgress(task.getTaskId(), generation, "PERSISTING_INDEX", 90);
                yield response.documents().stream().map(IngestDocumentResult::documentId)
                        .filter(id -> id != null && !id.isBlank()).findFirst().orElse(null);
            }
            case INGEST_DIRECTORY -> {
                 updateProgress(task.getTaskId(), generation, "SCANNING", 20);
                IngestResponse response = ingestionService.ingestDirectory(task.getSourcePath(), true, access);
                requireSuccessfulIngest(response, "目录导入");
                 updateProgress(task.getTaskId(), generation, "PERSISTING_INDEX", 90);
                yield null;
            }
            case SYNC -> {
                 updateProgress(task.getTaskId(), generation, "SCANNING", 20);
                ingestionService.syncWorkspace(access);
                 updateProgress(task.getTaskId(), generation, "PERSISTING_INDEX", 90);
                yield null;
            }
            case REBUILD -> {
                updateProgress(task.getTaskId(), generation, "SCANNING", 15);
                RebuildResult result = ingestionService.rebuildDocuments(access,
                         progress -> updateRebuildProgress(task.getTaskId(), generation, progress));
                if (result.failedFiles() > 0) {
                    throw new IllegalArgumentException("索引重建完成，但有 " + result.failedFiles() + " 个文件处理失败");
                }
                 updateProgress(task.getTaskId(), generation, "PERSISTING_INDEX", 90);
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

     private void updateProgress(String taskId, long generation, String stage, int progress) {
         repository.findById(taskId).ifPresent(task -> {
             if (!task.ownsLease(workerId, generation)) {
                 return;
             }
             java.util.regex.Matcher batchStart = java.util.regex.Pattern.compile(
                     "BATCH_START_(\\d+)_OF_(\\d+)_TOTALPAGES_(\\d+)_PAGES_(\\d+)_(\\d+)").matcher(stage);
             String persistedStage = stage;
             int persistedProgress = Math.max(task.getProgress(), progress);
             if (batchStart.matches()) {
                int batchIndex = Integer.parseInt(batchStart.group(1));
                int total = Integer.parseInt(batchStart.group(2));
                int totalPages = Integer.parseInt(batchStart.group(3));
                int startPage = Integer.parseInt(batchStart.group(4));
                int endPage = Integer.parseInt(batchStart.group(5));
                 task.updateBatchDetails(batchIndex, total, startPage, endPage);
                persistBatchStart(taskId, batchIndex, total, totalPages, startPage, endPage);
                 persistedStage = "REBUILDING";
             }
            java.util.regex.Matcher batch = java.util.regex.Pattern.compile(
                    "BATCH_(\\d+)_OF_(\\d+)_PAGES_(\\d+)_(\\d+)_CHUNKS_(\\d+)").matcher(stage);
            if (!batchStart.matches() && batch.matches()) {
                int completed = Integer.parseInt(batch.group(1));
                int total = Integer.parseInt(batch.group(2));
                int startPage = Integer.parseInt(batch.group(3));
                int endPage = Integer.parseInt(batch.group(4));
                int chunks = Integer.parseInt(batch.group(5));
                 task.updateBatchProgress(total, completed, completed, 0, chunks);
                 task.updateBatchDetails(completed, total, startPage, endPage);
                 persistedStage = "REBUILDING";
             } else if (!batchStart.matches()) {
                 persistedStage = stage;
             }
             task.updateProgress(persistedStage, persistedProgress);
             if (repository.updateProgress(taskId, DocumentTaskStatus.RUNNING, workerId, generation,
                     persistedStage, persistedProgress, task.getTotalItems(), task.getCompletedItems(),
                     task.getSucceededItems(), task.getFailedItems(), task.getResultChunks(), task.getCurrentBatch(),
                     task.getTotalBatches(), task.getCurrentStartPage(), task.getCurrentEndPage(),
                     Instant.now().plusSeconds(LEASE_SECONDS)) == 0) return;
             persistBatchProgressIfOwned(taskId, stage, batchStart, batch);
         });
     }

      private void persistBatchProgressIfOwned(String taskId, String stage,
                                               java.util.regex.Matcher batchStart,
                                               java.util.regex.Matcher batch) {
          if (batchStart.matches()) {
              persistBatchStart(taskId, Integer.parseInt(batchStart.group(1)), Integer.parseInt(batchStart.group(2)),
                      Integer.parseInt(batchStart.group(3)), Integer.parseInt(batchStart.group(4)), Integer.parseInt(batchStart.group(5)));
          }
          if (batch.matches()) persistBatchProgress(taskId, Integer.parseInt(batch.group(1)), Integer.parseInt(batch.group(2)),
                  Integer.parseInt(batch.group(3)), Integer.parseInt(batch.group(4)), Integer.parseInt(batch.group(5)));
      }

    private void persistBatchStart(String taskId, int batchIndex, int total, int totalPages,
                                   int startPage, int endPage) {
        if (batchRepository == null) return;
        int batchSize = Math.max(1, endPage - startPage + 1);
        List<DocumentTaskBatchEntity> batches = batchRepository.findByTaskIdOrderByBatchIndex(taskId);
        Set<String> existingIds = batches.stream()
                .map(DocumentTaskBatchEntity::getBatchId)
                .collect(Collectors.toSet());
        for (int index = 1; index <= total; index++) {
            String batchId = taskId + "-batch-" + index;
            if (!existingIds.contains(batchId)) {
                int plannedStart = (index - 1) * batchSize + 1;
                int plannedEnd = Math.min(totalPages, index * batchSize);
                batchRepository.save(new DocumentTaskBatchEntity(taskId, index, plannedStart, plannedEnd));
            }
        }
        batches.stream()
                .filter(batch -> batch.getBatchIndex() == batchIndex)
                .findFirst()
                .ifPresent(batch -> {
                    batch.start(startPage, endPage);
                    batchRepository.save(batch);
                });
    }

    private void failCurrentBatch(DocumentTaskEntity task, String message) {
        if (batchRepository == null || task.getCurrentBatch() <= 0) return;
        if (batchArtifactStore != null) {
            try {
                batchArtifactStore.delete(task.getTaskId(), task.getCurrentBatch());
            } catch (RuntimeException exception) {
                log.warn("Failed to delete document batch artifact taskId={} batch={} errorType={}",
                        task.getTaskId(), task.getCurrentBatch(), exception.getClass().getSimpleName());
            }
        }
        batchRepository.findById(task.getTaskId() + "-batch-" + task.getCurrentBatch()).ifPresent(batch -> {
            batch.fail(message);
            batchRepository.save(batch);
        });
    }

    private void deleteBatchArtifacts(String taskId) {
        if (batchArtifactStore == null) return;
        try {
            batchArtifactStore.deleteAll(taskId);
        } catch (RuntimeException exception) {
            // 临时产物可由保留策略再次清理，不能因此改变已经确定的任务结果。
            log.warn("Failed to delete document batch artifacts taskId={} errorType={}", taskId,
                    exception.getClass().getSimpleName());
        }
    }

    private void persistBatchProgress(String taskId, int batchIndex, int total, int startPage, int endPage,
                                      int chunks) {
        if (batchRepository == null) return;
        List<DocumentTaskBatchEntity> batches = batchRepository.findByTaskIdOrderByBatchIndex(taskId);
        Map<Integer, DocumentTaskBatchEntity> byIndex = batches.stream()
                .collect(Collectors.toMap(DocumentTaskBatchEntity::getBatchIndex, batch -> batch, (a, b) -> a));
        for (int index = 1; index <= total; index++) {
            if (byIndex.containsKey(index)) continue;
            batchRepository.save(new DocumentTaskBatchEntity(taskId, index,
                    index == batchIndex ? startPage : 0, index == batchIndex ? endPage : 0));
        }
        DocumentTaskBatchEntity batch = byIndex.get(batchIndex);
        if (batch != null) {
            int previousChunks = batches.stream()
                    .filter(previous -> previous.getBatchIndex() < batchIndex)
                    .filter(previous -> previous.getStatus() == DocumentTaskBatchStatus.SUCCEEDED)
                    .mapToInt(DocumentTaskBatchEntity::getChunkCount).sum();
            batch.succeed(Math.max(0, chunks - previousChunks));
            batchRepository.save(batch);
        }
    }

     private void updateRebuildProgress(String taskId, long generation, RebuildProgress progress) {
         repository.findById(taskId).ifPresent(task -> {
             if (!task.ownsLease(workerId, generation)) {
                 return;
             }
            int percentage = progress.totalFiles() == 0 ? 85
                    : 20 + (int) Math.floor(progress.completedFiles() * 65.0 / progress.totalFiles());
            task.updateProgress("REBUILDING", percentage);
            task.updateBatchProgress(progress.totalFiles(), progress.completedFiles(), progress.succeededFiles(),
                    progress.failedFiles(), progress.chunks());
             repository.updateProgress(taskId, DocumentTaskStatus.RUNNING, workerId, generation,
                     task.getStage(), task.getProgress(), task.getTotalItems(), task.getCompletedItems(),
                     task.getSucceededItems(), task.getFailedItems(), task.getResultChunks(), task.getCurrentBatch(),
                     task.getTotalBatches(), task.getCurrentStartPage(), task.getCurrentEndPage(),
                     Instant.now().plusSeconds(LEASE_SECONDS));
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
                     repository.renewLease(task.getTaskId(), DocumentTaskStatus.RUNNING, workerId,
                             task.getGeneration(), expiresAt);
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
