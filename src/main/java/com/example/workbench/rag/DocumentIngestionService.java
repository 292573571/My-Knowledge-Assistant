package com.example.workbench.rag;

import com.example.workbench.workspace.DocumentVisibility;
import com.example.workbench.workspace.WorkspaceAccessContext;
import com.example.workbench.workspace.WorkspaceType;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Service
public class DocumentIngestionService {

    private static final Logger log = LoggerFactory.getLogger(DocumentIngestionService.class);
    private static final Path DEFAULT_DOCS_DIRECTORY = Path.of("docs");

    private final List<SourceDocument> documents = new ArrayList<>();
    private final VectorStore vectorStore;
    private final DocumentIndexStore documentIndexStore;
    private final DocumentParserRouter documentParserRouter;
    private final DocumentChunkerRouter documentChunkerRouter;
    private final Path docsDirectory;
    private final int pdfBatchPages;
    private DocumentTaskBatchArtifactStore batchArtifactStore;
    private DocumentTaskRepository documentTaskRepository;
    // 替代方法级 synchronized：仅保护对全局 documents / vectorStore / documentIndexStore 的变更，
    // 解析与落盘等慢速 IO 在锁外进行，避免大文件解析期间串行阻塞其他摄入操作。
    private final ReentrantLock ingestionLock = new ReentrantLock();
    private final IngestionPathResolver ingestionPathResolver;

    /**
     * 注入异步任务批次产物存储。
     *
     * @param batchArtifactStore 批次产物存储
     */
    @Autowired(required = false)
    public void setBatchArtifactStore(DocumentTaskBatchArtifactStore batchArtifactStore) {
        this.batchArtifactStore = batchArtifactStore;
    }

    @Autowired(required = false)
    public void setDocumentTaskRepository(DocumentTaskRepository documentTaskRepository) {
        this.documentTaskRepository = documentTaskRepository;
    }

    @Autowired
    public DocumentIngestionService(
            VectorStore vectorStore,
            DocumentIndexStore documentIndexStore,
            DocumentParserRouter documentParserRouter,
            DocumentChunkerRouter documentChunkerRouter,
            @Value("${workbench.pdf.batch-pages:50}") int pdfBatchPages
    ) {
        this(vectorStore, documentIndexStore, documentParserRouter, documentChunkerRouter, DEFAULT_DOCS_DIRECTORY, pdfBatchPages);
    }

    DocumentIngestionService(
            VectorStore vectorStore,
            DocumentIndexStore documentIndexStore,
            DocumentParserRouter documentParserRouter,
            DocumentChunkerRouter documentChunkerRouter,
            Path docsDirectory
    ) {
        this(vectorStore, documentIndexStore, documentParserRouter, documentChunkerRouter, docsDirectory, 50);
    }

    DocumentIngestionService(
            VectorStore vectorStore,
            DocumentIndexStore documentIndexStore,
            DocumentParserRouter documentParserRouter,
            DocumentChunkerRouter documentChunkerRouter,
            Path docsDirectory,
            int pdfBatchPages
    ) {
        this.vectorStore = vectorStore;
        this.documentIndexStore = documentIndexStore;
        this.documentParserRouter = documentParserRouter;
        this.documentChunkerRouter = documentChunkerRouter;
        this.docsDirectory = docsDirectory.toAbsolutePath().normalize();
        this.pdfBatchPages = Math.max(1, pdfBatchPages);
        this.ingestionPathResolver = new IngestionPathResolver(this.docsDirectory);
    }

    public IngestResult ingestDocsDirectory() throws IOException {
        long startedAt = System.currentTimeMillis();
        if (!Files.exists(docsDirectory)) {
            log.info("Document ingest docs directory skipped reason=directory_not_found");
            return new IngestResult(0, 0);
        }

        log.info("Document ingest docs directory started");
        // 全量导入以磁盘 docs/ 为唯一事实来源，先重建进程内列表再整体替换向量库与索引。
        documents.clear();
        List<DocumentIndexEntry> indexEntries = new ArrayList<>();
        List<Path> supportedFiles;

        try (var paths = Files.walk(docsDirectory)) {
            supportedFiles = paths.filter(Files::isRegularFile)
                    .filter(ingestionPathResolver::isIndexableDocument)
                    .sorted()
                    .toList();
        }

        int documentCount = 0;

        for (Path path : supportedFiles) {
            IngestedFile ingestedFile = ingestFile(path);
            if (ingestedFile.chunkCount() > 0) {
                indexEntries.add(ingestedFile.indexEntry());
                documents.addAll(ingestedFile.documents());
                documentCount += ingestedFile.chunkCount();
            }
        }

        // replaceAll 保证全量导入后内存、向量库、索引三者使用同一批文档。
        vectorStore.replaceAll(documents);
        documentIndexStore.replaceAll(indexEntries);

        log.info(
                "Document ingest docs directory completed files={} chunks={} durationMs={}",
                supportedFiles.size(),
                documentCount,
                System.currentTimeMillis() - startedAt
        );
        return new IngestResult(supportedFiles.size(), documentCount);
    }

    public IngestResponse ingestDocument(String path, boolean force) {
        long startedAt = System.currentTimeMillis();
        log.info("Document ingest file request started force={}", force);
        // 所有外部传入路径都限制在 docs/ 内，防止 API 被用于读取工作区任意文件。
        Path documentPath = ingestionPathResolver.resolveAllowedPath(path);
        if (!Files.isRegularFile(documentPath)) {
            throw new IllegalArgumentException("Document path must be a file: " + path);
        }

        IngestDocumentResult result = ingestPath(documentPath, force);
        log.info(
                "Document ingest file request completed status={} chunks={} durationMs={}",
                result.status(),
                result.chunks(),
                System.currentTimeMillis() - startedAt
        );
        return responseFrom(List.of(result));
    }

    /**
     * 将数据库正式笔记投影到检索副本，显式使用事实表中的 owner/workspace，避免从文件路径推断隔离边界。
     */
    public void ingestFormalNote(String path, String ownerUserId, String workspaceId) {
        Path notePath = ingestionPathResolver.resolveAllowedPath(path);
        if (!Files.isRegularFile(notePath)) {
            throw new IllegalArgumentException("Formal note path must be a file: " + path);
        }
        // 解析与投影计算不触碰共享状态，移出锁外，避免大文件解析期间阻塞其他摄入操作。
        IngestedFile parsed = ingestFile(notePath);
        if (parsed.indexEntry() == null || parsed.documents().isEmpty()) {
            throw new IllegalArgumentException("Formal note content cannot be empty");
        }

        String scopedHash = ingestionPathResolver.sha256(workspaceId + "\n" + parsed.indexEntry().contentHash());
        String documentId = scopedHash.substring(0, 16);
        DocumentVisibility visibility = workspaceId != null && workspaceId.startsWith("personal-")
                ? DocumentVisibility.PRIVATE : DocumentVisibility.WORKSPACE;
        String relativePath = ingestionPathResolver.workspaceRelativePath(notePath).replace('\\', '/');
        List<SourceDocument> projectedDocuments = parsed.documents().stream().map(source -> new SourceDocument(
                documentId + "#chunk-" + source.chunkIndex(), source.content(), source.title(), source.source(),
                relativePath, source.chunkIndex(), documentId, parsed.indexEntry().fileName(), scopedHash,
                source.score(), source.headingPath(), source.headingLevel(), source.startOffset(), source.endOffset(),
                source.chunkType(), "FORMAL_NOTE", ownerUserId, workspaceId, visibility, source.pageNumber()
        )).toList();
        DocumentIndexEntry projectedEntry = new DocumentIndexEntry(
                documentId, parsed.indexEntry().fileName(), relativePath, scopedHash, projectedDocuments.size(),
                java.time.Instant.now(), "FORMAL_NOTE", "INDEXED", ownerUserId, workspaceId, visibility
        );

        ingestionLock.lock();
        try {
            documentIndexStore.list().stream()
                    .filter(existing -> existing.path().equals(relativePath) && existing.workspaceId().equals(workspaceId))
                    .toList().forEach(this::removeIndexedDocument);
            documents.removeIf(document -> document.documentId().equals(documentId));
            documents.addAll(projectedDocuments);
            vectorStore.addAll(projectedDocuments);
            documentIndexStore.upsertAll(List.of(projectedEntry));
        } finally {
            ingestionLock.unlock();
        }
    }

    public IngestResponse ingestDocument(String path, boolean force, WorkspaceAccessContext access) {
        ingestionPathResolver.requireWorkspaceWrite(access);
        Path documentPath = ingestionPathResolver.resolveAllowedPath(path);
        if (!Files.isRegularFile(documentPath)) {
            throw new IllegalArgumentException("Document path must be a file: " + path);
        }
        return responseFrom(List.of(ingestWorkspacePath(documentPath, force, access)));
    }

    public IngestResponse ingestDirectory(String path, boolean force) throws IOException {
        long startedAt = System.currentTimeMillis();
        Path directoryPath = path == null || path.isBlank()
                ? docsDirectory
                : ingestionPathResolver.resolveAllowedPath(path);
        log.info("Document ingest directory request started force={}", force);

        if (!Files.exists(directoryPath)) {
            log.info("Document ingest directory request skipped reason=directory_not_found");
            return responseFrom(List.of());
        }

        if (!Files.isDirectory(directoryPath)) {
            throw new IllegalArgumentException("Directory path must be a directory: " + path);
        }

        List<Path> supportedFiles;
        try (var paths = Files.walk(directoryPath)) {
            supportedFiles = paths.filter(Files::isRegularFile)
                    .filter(ingestionPathResolver::isIndexableDocument)
                    .sorted()
                    .toList();
        }

        List<IngestDocumentResult> results = supportedFiles.stream()
                .map(file -> ingestPath(file, force))
                .toList();

        IngestResponse response = responseFrom(results);
        log.info(
                "Document ingest directory request completed files={} imported={} skipped={} failed={} chunks={} durationMs={}",
                response.files(),
                response.imported(),
                response.skipped(),
                response.failed(),
                response.documents(),
                System.currentTimeMillis() - startedAt
        );
        return response;
    }

    public IngestResponse ingestDirectory(String path, boolean force, WorkspaceAccessContext access) throws IOException {
        ingestionPathResolver.requireWorkspaceWrite(access);
        Path directoryPath = path == null || path.isBlank()
                ? ingestionPathResolver.workspaceDirectory(access)
                : ingestionPathResolver.resolveAllowedPath(path);
        if (!Files.exists(directoryPath)) {
            return responseFrom(List.of());
        }
        if (!Files.isDirectory(directoryPath)) {
            throw new IllegalArgumentException("Directory path must be a directory: " + path);
        }
        List<IngestDocumentResult> results = supportedFiles(directoryPath).stream()
                .map(file -> ingestWorkspacePath(file, force, access))
                .toList();
        return responseFrom(results);
    }

    public void ingest(SourceDocument document) {
        documents.add(document);
    }

    public List<SourceDocument> listDocuments() {
        return Collections.unmodifiableList(documents);
    }

    public WorkspaceDocumentUploadResponse uploadWorkspaceDocument(
            WorkspaceAccessContext access,
            MultipartFile file
    ) {
        if (!access.canWrite()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "当前角色不能上传空间文档");
        }
        String originalFileName = ingestionPathResolver.safeOriginalFileName(file.getOriginalFilename());
        byte[] content = ingestionPathResolver.uploadedContent(file);
        String extension = originalFileName.substring(originalFileName.lastIndexOf('.')).toLowerCase();
        Path workspaceDirectory = docsDirectory.resolve("workspaces").resolve(access.workspaceId()).normalize();
        Path target = workspaceDirectory.resolve(UUID.randomUUID() + extension).normalize();
        if (!target.startsWith(workspaceDirectory)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "上传文件名无效");
        }

        try {
            Files.createDirectories(workspaceDirectory);
            Files.write(target, content, java.nio.file.StandardOpenOption.CREATE_NEW);
            IngestedFile ingested = ingestWorkspaceFile(target, originalFileName, access);
            if (ingested.indexEntry() == null || ingested.chunkCount() == 0) {
                Files.deleteIfExists(target);
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "文档内容不能为空");
            }
            // 仅把向量库与索引的变更放进锁内，解析与落盘已在锁外完成。
            ingestionLock.lock();
            try {
                documents.addAll(ingested.documents());
                vectorStore.addAll(ingested.documents());
                documentIndexStore.upsertAll(List.of(ingested.indexEntry()));
            } finally {
                ingestionLock.unlock();
            }
            DocumentIndexEntry entry = ingested.indexEntry();
            return new WorkspaceDocumentUploadResponse(entry.documentId(), entry.fileName(), entry.path(),
                    entry.chunkCount(), entry.workspaceId(), entry.visibility());
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to save workspace document", exception);
        } catch (RuntimeException exception) {
            try {
                Files.deleteIfExists(target);
            } catch (IOException ignored) {
                // Preserve the original upload/indexing failure.
            }
            throw exception;
        }
    }

    /**
     * 将上传文件持久化到知识空间目录，解析和索引由异步任务继续执行。
     *
     * @param access 空间访问上下文
     * @param file 上传文件
     * @return 已持久化的上传文件信息
     */
    public PendingWorkspaceUpload saveWorkspaceUpload(WorkspaceAccessContext access, MultipartFile file) {
        if (!access.canWrite()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "当前角色不能上传空间文档");
        }
        ingestionPathResolver.validateUploadedFile(file);
        String originalFileName = ingestionPathResolver.safeOriginalFileName(file.getOriginalFilename());
        String extension = originalFileName.substring(originalFileName.lastIndexOf('.')).toLowerCase();
        Path workspaceDirectory = docsDirectory.resolve("workspaces").resolve(access.workspaceId()).normalize();
        Path target = workspaceDirectory.resolve(UUID.randomUUID() + extension).normalize();
        if (!target.startsWith(workspaceDirectory)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "上传文件名无效");
        }

        try {
            Files.createDirectories(workspaceDirectory);
            file.transferTo(target);
            return new PendingWorkspaceUpload(originalFileName, ingestionPathResolver.workspaceRelativePath(target).replace('\\', '/'));
        } catch (IOException exception) {
            try {
                Files.deleteIfExists(target);
            } catch (IOException ignored) {
                // Preserve the original upload failure.
            }
            throw new IllegalStateException("Failed to save workspace document", exception);
        }
    }

    /**
     * 解析并索引已经持久化的空间文档。
     *
     * @param access 空间访问上下文
     * @param sourcePath 源文件相对路径
     * @param originalFileName 原始文件名
     * @param progress 任务阶段和进度回调
     * @return 完成索引后的文档信息
     */
    public WorkspaceDocumentUploadResponse indexWorkspaceUpload(
            WorkspaceAccessContext access,
            String sourcePath,
            String originalFileName,
            BiConsumer<String, Integer> progress
    ) {
        return indexWorkspaceUpload(access, sourcePath, originalFileName, null, progress);
    }

    /**
     * 解析并索引支持批次恢复的异步上传文档。
     *
     * @param access 空间访问上下文
     * @param sourcePath 源文件相对路径
     * @param originalFileName 原始文件名
     * @param taskId 异步任务标识
     * @param progress 任务进度回调
     * @return 完成索引后的文档信息
     */
    public WorkspaceDocumentUploadResponse indexWorkspaceUpload(
            WorkspaceAccessContext access, String sourcePath, String originalFileName, String taskId,
            BiConsumer<String, Integer> progress
    ) {
        ingestionPathResolver.requireWorkspaceWrite(access);
        Path source = ingestionPathResolver.resolveIndexedPath(sourcePath);
        // 解析（含分批流式解析与 chunk 收集）不触碰共享状态，移出锁外，避免大文件解析阻塞其他摄入。
        IngestedFile ingested = ingestWorkspaceFile(source, originalFileName, access, taskId, progress);
        if (ingested.indexEntry() == null || ingested.chunkCount() == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "文档内容不能为空");
        }
        ingestionLock.lock();
        List<String> streamedIds = new ArrayList<>();
        DocumentIndexEntry previousEntry = null;
        try {
            previousEntry = documentIndexStore.list().stream()
                    .filter(entry -> entry.path().equals(ingestionPathResolver.workspaceRelativePath(source).replace('\\', '/')))
                    .findFirst()
                    .orElse(null);
            for (List<SourceDocument> batch : partitionByPageBatch(ingested.documents())) {
                streamedIds.addAll(batch.stream().map(SourceDocument::id).toList());
                vectorStore.addAll(batch);
            }
            documents.removeIf(document -> document.documentId().equals(ingested.indexEntry().documentId()));
            documents.addAll(ingested.documents());
            progress.accept("VECTORIZING", 70);
            progress.accept("PERSISTING_INDEX", 90);
            documentIndexStore.upsertAll(List.of(ingested.indexEntry()));
            if (previousEntry != null && !previousEntry.documentId().equals(ingested.indexEntry().documentId())) {
                vectorStore.deleteByIds(chunkIds(previousEntry));
            }
            DocumentIndexEntry entry = ingested.indexEntry();
            if (previousEntry != null && !previousEntry.path().equals(entry.path())) {
                // 相同内容重新上传后仅保留最新受管源文件，避免随机文件名不断产生磁盘孤儿。
                deleteWorkspaceUploadSource(previousEntry);
            }
            return new WorkspaceDocumentUploadResponse(entry.documentId(), entry.fileName(), entry.path(),
                    entry.chunkCount(), entry.workspaceId(), entry.visibility());
        } catch (RuntimeException exception) {
            vectorStore.deleteByIds(streamedIds);
            documents.removeIf(document -> streamedIds.contains(document.id()));
            if (previousEntry == null) {
                documentIndexStore.delete(ingested.indexEntry().documentId(), ingested.indexEntry().workspaceId());
            } else {
                documentIndexStore.upsertAll(List.of(previousEntry));
            }
            throw exception;
        } finally {
            ingestionLock.unlock();
        }
    }

    private List<List<SourceDocument>> partitionByPageBatch(List<SourceDocument> sourceDocuments) {
        if (sourceDocuments.isEmpty()) return List.of();
        return new ArrayList<>(sourceDocuments.stream().collect(Collectors.groupingBy(
                document -> document.pageNumber() <= 0 ? 0 : (document.pageNumber() - 1) / pdfBatchPages,
                java.util.LinkedHashMap::new, Collectors.toList())).values());
    }

    public List<DocumentIndexEntry> listIndexedDocuments() {
        // 旧版本可能留下同一路径的重复索引；列表只展示该路径最新的有效版本。
        Map<String, DocumentIndexEntry> latestByPath = new HashMap<>();
        documentIndexStore.list().forEach(entry -> latestByPath.merge(
                entry.path().replace('\\', '/'),
                entry,
                 (current, candidate) -> current.ingestedAt().compareTo(candidate.ingestedAt()) >= 0 ? current : candidate
        ));
        return latestByPath.values().stream()
                .filter(entry -> !isLearningRecordEntry(entry))
                .sorted(java.util.Comparator.comparing(DocumentIndexEntry::fileName))
                .toList();
    }

    public List<DocumentIndexEntry> listVisibleIndexedDocuments(String ownerUserId) {
        return listIndexedDocuments().stream()
                .filter(entry -> canRead(entry, ownerUserId, null))
                .toList();
    }

    public List<DocumentIndexEntry> listVisibleIndexedDocuments(WorkspaceAccessContext access) {
        return listIndexedDocuments().stream().filter(entry -> canRead(entry, access.userId(), access.workspaceId())).toList();
    }

    public List<DocumentIndexEntry> listWorkspaceIndexedDocuments(WorkspaceAccessContext access) {
        return listIndexedDocuments().stream()
                .filter(entry -> entry.workspaceId().equals(access.workspaceId()))
                .toList();
    }

    /**
     * 判断指定文档是否仍存在于当前空间的知识库索引中。
     *
     * @param documentId 文档标识
     * @param access 空间访问上下文
     * @return 文档仍存在且当前用户可读时返回 true
     */
    public boolean isIndexedDocumentAvailable(String documentId, WorkspaceAccessContext access) {
        if (documentId == null || documentId.isBlank()) {
            return false;
        }
        return documentIndexStore.list().stream()
                .filter(entry -> entry.documentId().equals(documentId))
                .anyMatch(entry -> canRead(entry, access.userId(), access.workspaceId()));
    }

    public List<DocumentIndexEntry> listPublicIndexedDocuments() {
        return listVisibleIndexedDocuments("");
    }

    private boolean isLearningRecordEntry(DocumentIndexEntry entry) {
        return "LEARNING_RECORD".equals(entry.category())
                || entry.path().replace('\\', '/').startsWith("docs/learning-records/");
    }

    public DocumentContentResponse documentContent(String documentId, String ownerUserId) {
        DocumentIndexEntry entry = documentIndexStore.list().stream()
                .filter(item -> item.documentId().equals(documentId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Indexed document not found: " + documentId));
        if (!canRead(entry, ownerUserId, null)) {
            throw new IllegalArgumentException("Indexed document not found: " + documentId);
        }
        return readDocumentContent(entry);
    }

    private DocumentContentResponse readDocumentContent(DocumentIndexEntry entry) {
        String documentId = entry.documentId();
        Path documentPath = ingestionPathResolver.resolveIndexedPath(entry.path());
        try {
            Path realDocsDirectory = docsDirectory.toRealPath();
            Path realDocumentPath = documentPath.toRealPath();
            if (!realDocumentPath.startsWith(realDocsDirectory) || !Files.isRegularFile(realDocumentPath)) {
                throw new IllegalArgumentException("Document source file is not available: " + documentId);
            }
            return new DocumentContentResponse(
                    entry.documentId(),
                    entry.fileName(),
                     entry.path(),
                     entry.category(),
                     documentParserRouter.parse(entry.fileName(), Files.readAllBytes(realDocumentPath)).content(),
                     true
            );
        } catch (java.nio.file.NoSuchFileException exception) {
            return recoveredDocumentContent(entry);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to read document source: " + documentId, exception);
        }
    }

    private DocumentContentResponse recoveredDocumentContent(DocumentIndexEntry entry) {
        if (!(vectorStore instanceof ChromaVectorStoreAdapter chromaVectorStore)) {
            throw new IllegalArgumentException("文档源文件和索引正文均不可用，请重新上传：" + entry.fileName());
        }
        List<SourceDocument> chunks = chromaVectorStore.documentsByIds(chunkIds(entry)).stream()
                .filter(chunk -> entry.documentId().equals(chunk.documentId()))
                .sorted(java.util.Comparator.comparingInt(SourceDocument::chunkIndex))
                .toList();
        if (chunks.isEmpty()) {
            throw new IllegalArgumentException("文档源文件和索引正文均不可用，请重新上传：" + entry.fileName());
        }
        StringBuilder content = new StringBuilder();
        int previousEndOffset = 0;
        for (SourceDocument chunk : chunks) {
            int overlap = Math.max(0, previousEndOffset - chunk.startOffset());
            int overlapInContent = Math.min(overlap, chunk.content().length());
            if (!content.isEmpty() && overlapInContent == 0) {
                content.append("\n\n");
            }
            content.append(chunk.content().substring(overlapInContent));
            previousEndOffset = Math.max(previousEndOffset, chunk.endOffset());
        }
        return new DocumentContentResponse(entry.documentId(), entry.fileName(), entry.path(), entry.category(),
                content.toString(), false);
    }

    public DocumentContentResponse documentContent(String documentId, WorkspaceAccessContext access) {
        DocumentIndexEntry entry = indexedDocument(documentId);
        if (!canRead(entry, access.userId(), access.workspaceId())) {
            throw new IllegalArgumentException("Indexed document not found: " + documentId);
        }
        return readDocumentContent(entry);
    }

    /**
     * 读取已经持久化的上传源文件，并阻止目录穿越和符号链接逃逸。
     *
     * @param sourcePath 任务中保存的源文件路径
     * @param fileName 用户上传时的原始文件名
     * @return 源文件内容
     */
    public DocumentSourceFile sourceFile(String sourcePath, String fileName) {
        Path source = ingestionPathResolver.resolveIndexedPath(sourcePath);
        try {
            Path realDocsDirectory = docsDirectory.toRealPath();
            Path realSource = source.toRealPath();
            if (!realSource.startsWith(realDocsDirectory) || !Files.isRegularFile(realSource)) {
                throw new IllegalArgumentException("文档源文件不可用：" + fileName);
            }
            return new DocumentSourceFile(fileName, Files.readAllBytes(realSource));
        } catch (java.nio.file.NoSuchFileException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "文档源文件已缺失：" + fileName);
        } catch (IOException exception) {
            throw new IllegalStateException("读取文档源文件失败：" + fileName, exception);
        }
    }

    /**
     * 按文档索引读取当前保留的源文件，供历史上传记录在旧文件已清理时回退使用。
     *
     * @param documentId 文档标识
     * @param fileName 下载时展示的文件名
     * @param access 空间访问上下文
     * @return 源文件内容
     */
    public DocumentSourceFile sourceFile(String documentId, String fileName, WorkspaceAccessContext access) {
        DocumentIndexEntry entry = indexedDocument(documentId);
        if (!canRead(entry, access.userId(), access.workspaceId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "文档源文件不存在");
        }
        return sourceFile(entry.path(), fileName);
    }

    public void deleteDocument(String documentId, String ownerUserId, boolean canManagePublicDocuments) {
        log.info("Document delete started documentId={}", documentId);
        ingestionLock.lock();
        try {
            DocumentIndexEntry entry = documentIndexStore.list().stream()
                    .filter(item -> item.documentId().equals(documentId))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Indexed document not found: " + documentId));
            boolean ownsDocument = !entry.ownerUserId().isBlank() && entry.ownerUserId().equals(ownerUserId);
            boolean mayDeletePublicDocument = entry.ownerUserId().isBlank() && canManagePublicDocuments;
            if (!ownsDocument && !mayDeletePublicDocument) {
                throw new IllegalArgumentException("Indexed document not found: " + documentId);
            }
            deleteWorkspaceUploadSource(entry);
            removeIndexedDocument(entry);
        } finally {
            ingestionLock.unlock();
        }
        log.info("Document delete completed documentId={} remainingChunks={}", documentId, documents.size());
    }

    public void deleteDocument(String documentId, WorkspaceAccessContext access, boolean canManagePublicDocuments) {
        ingestionLock.lock();
        try {
            DocumentIndexEntry entry = indexedDocument(documentId);
            boolean mayDeletePrivate = entry.visibility() == DocumentVisibility.PRIVATE && entry.ownerUserId().equals(access.userId());
            boolean mayDeleteWorkspace = entry.visibility() == DocumentVisibility.WORKSPACE
                    && entry.workspaceId().equals(access.workspaceId()) && access.canWrite();
            boolean mayDeletePublic = entry.visibility() == DocumentVisibility.PUBLIC && canManagePublicDocuments
                    && entry.workspaceId().equals(access.workspaceId()) && access.canWrite();
            if (!mayDeletePrivate && !mayDeleteWorkspace && !mayDeletePublic) {
                throw new IllegalArgumentException("Indexed document not found: " + documentId);
            }
            deleteWorkspaceUploadSource(entry);
            removeIndexedDocument(entry);
        } finally {
            ingestionLock.unlock();
        }
    }

    private boolean canRead(DocumentIndexEntry entry, String ownerUserId, String workspaceId) {
        if (entry.visibility() == DocumentVisibility.PUBLIC) {
            return true;
        }
        if (entry.visibility() == DocumentVisibility.PRIVATE) {
            return ownerUserId != null && !ownerUserId.isBlank()
                    && entry.ownerUserId().equals(ownerUserId)
                    && (workspaceId == null || entry.workspaceId().equals(workspaceId));
        }
        return workspaceId != null && !workspaceId.isBlank() && entry.workspaceId().equals(workspaceId);
    }

    private DocumentIndexEntry indexedDocument(String documentId) {
        return documentIndexStore.list().stream()
                .filter(item -> item.documentId().equals(documentId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Indexed document not found: " + documentId));
    }

    public void deleteIndexedPath(String path) {
        String normalizedPath = path.replace('\\', '/');
        ingestionLock.lock();
        try {
            documentIndexStore.list().stream()
                    .filter(entry -> entry.path().replace('\\', '/').equals(normalizedPath))
                    .toList()
                    .forEach(this::removeIndexedDocument);
        } finally {
            ingestionLock.unlock();
        }
    }

    public RebuildResult rebuildDocuments() throws IOException {
        ingestionLock.lock();
        try {
        // 重建会清空全部索引后从 docs/ 重新导入，适用于向量库或索引不一致时恢复。
        long startedAt = System.currentTimeMillis();
        List<DocumentIndexEntry> indexedDocuments = documentIndexStore.list();
        List<String> indexedChunkIds = indexedDocuments.stream()
                .flatMap(entry -> chunkIds(entry).stream())
                .toList();
        log.info(
                "Document rebuild started stage=prepare_clear indexedDocuments={} indexedChunks={}",
                indexedDocuments.size(),
                indexedChunkIds.size()
        );

        try {
            log.info("Document rebuild stage=clear_vector_store indexedChunks={}", indexedChunkIds.size());
            documents.clear();
            vectorStore.deleteByIds(indexedChunkIds);
            vectorStore.clear();
            documentIndexStore.clear();
            log.info("Document rebuild stage=clear_completed clearedDocuments={} clearedChunks={}", indexedDocuments.size(), indexedChunkIds.size());

            log.info("Document rebuild stage=ingest_started");
            IngestResult result = ingestDocsDirectory();
            long durationMs = System.currentTimeMillis() - startedAt;
            log.info(
                    "Document rebuild completed status=success clearedDocuments={} clearedChunks={} files={} chunks={} durationMs={}",
                    indexedDocuments.size(),
                    indexedChunkIds.size(),
                    result.files(),
                    result.documents(),
                    durationMs
            );
            return new RebuildResult(
                    "success",
                    indexedDocuments.size(),
                    indexedChunkIds.size(),
                    result.files(),
                    0,
                    result.documents(),
                    durationMs
            );
        } catch (RuntimeException exception) {
            log.error(
                    "Document rebuild failed status=failed clearedDocuments={} clearedChunks={} durationMs={} errorType={}",
                    indexedDocuments.size(),
                    indexedChunkIds.size(),
                    System.currentTimeMillis() - startedAt,
                    exception.getClass().getSimpleName()
            );
            throw exception;
        }
        } finally {
            ingestionLock.unlock();
        }
    }

    public RebuildResult rebuildDocuments(WorkspaceAccessContext access) throws IOException {
        return rebuildDocuments(access, progress -> { });
    }

    /**
     * 重建空间索引，并在每个源文件处理后报告可持久化的进度统计。
     *
     * @param access 空间访问上下文
     * @param progress 文件处理进度回调
     * @return 重建结果
     * @throws IOException 扫描空间目录失败时抛出
     */
    public RebuildResult rebuildDocuments(
            WorkspaceAccessContext access, Consumer<RebuildProgress> progress) throws IOException {
        ingestionLock.lock();
        try {
        ingestionPathResolver.requireWorkspaceWrite(access);
        long startedAt = System.currentTimeMillis();
        List<DocumentIndexEntry> existingEntries = documentIndexStore.list().stream()
                .filter(entry -> entry.workspaceId().equals(access.workspaceId()))
                .toList();
        int clearedChunks = existingEntries.stream().mapToInt(DocumentIndexEntry::chunkCount).sum();
        List<Path> existingSources = existingEntries.stream()
                .map(entry -> ingestionPathResolver.resolveIndexedPath(entry.path()))
                .filter(Files::isRegularFile)
                .distinct()
                .toList();
        existingEntries.forEach(this::removeIndexedDocument);

        Map<String, String> originalNamesByPath = existingEntries.stream()
                .collect(Collectors.toMap(
                        entry -> entry.path().replace('\\', '/'),
                        entry -> originalFileName(entry.path(), access.workspaceId(), entry.fileName()),
                        (left, right) -> left));
        List<Path> sources = new ArrayList<>(existingSources);
        for (Path source : supportedFiles(ingestionPathResolver.workspaceDirectory(access))) {
            if (!sources.contains(source)) {
                sources.add(source);
            }
        }
        List<IngestDocumentResult> results = new ArrayList<>();
        progress.accept(new RebuildProgress(sources.size(), 0, 0, 0, 0));
        for (Path source : sources) {
            String sourcePath = ingestionPathResolver.workspaceRelativePath(source).replace('\\', '/');
            try {
                results.add(ingestWorkspacePath(source, true, access,
                        originalNamesByPath.getOrDefault(sourcePath, source.getFileName().toString())));
            } catch (RuntimeException exception) {
                results.add(new IngestDocumentResult(source.getFileName().toString(), sourcePath, null,
                        "failed", 0, exception.getMessage()));
            }
            IngestResponse current = responseFrom(results);
            progress.accept(new RebuildProgress(sources.size(), results.size(), current.imported(),
                    current.failed(), current.chunks()));
        }
        IngestResponse response = responseFrom(results);
        return new RebuildResult("success", existingEntries.size(), clearedChunks,
                response.imported(), response.failed(), response.chunks(), System.currentTimeMillis() - startedAt);
        } finally {
            ingestionLock.unlock();
        }
    }

    public SyncResult syncDocsDirectory() throws IOException {
        ingestionLock.lock();
        try {
        // 增量同步通过路径和内容哈希识别新增、修改、移动、删除和未变化文件。
        long startedAt = System.currentTimeMillis();
        List<DocumentIndexEntry> existingEntries = documentIndexStore.list();
        Map<String, DocumentIndexEntry> entriesByPath = existingEntries.stream()
                .collect(Collectors.toMap(DocumentIndexEntry::path, entry -> entry, (left, right) -> left, HashMap::new));
        Map<String, DocumentIndexEntry> entriesByHash = existingEntries.stream()
                .collect(Collectors.toMap(DocumentIndexEntry::contentHash, entry -> entry, (left, right) -> left, HashMap::new));
        Set<String> processedExistingDocumentIds = new java.util.HashSet<>();
        List<Path> files = supportedFiles(docsDirectory);
        Set<String> scannedPaths = files.stream().map(ingestionPathResolver::workspaceRelativePath).collect(Collectors.toSet());
        int addedFiles = 0;
        int updatedFiles = 0;
        int unchangedFiles = 0;
        int deletedFiles = 0;
        int addedChunks = 0;
        int deletedChunks = 0;

        log.info(
                "Document sync started stage=scan indexedDocuments={} scannedFiles={}",
                existingEntries.size(),
                files.size()
        );

        try {
            for (Path path : files) {
                String relativePath = ingestionPathResolver.workspaceRelativePath(path);
                IngestedFile ingestedFile = ingestFile(path);
                DocumentIndexEntry existingEntry = entriesByPath.get(relativePath);

                if (ingestedFile.indexEntry() == null || ingestedFile.chunkCount() == 0) {
                    if (existingEntry != null) {
                        int removedChunks = removeIndexedDocument(existingEntry);
                        deletedFiles++;
                        deletedChunks += removedChunks;
                        log.info("Document sync removed empty file deletedChunks={}", removedChunks);
                    }
                    continue;
                }

                DocumentIndexEntry candidate = ingestedFile.indexEntry();
                if (existingEntry == null) {
                    existingEntry = entriesByHash.get(candidate.contentHash());
                }
                if (existingEntry != null && existingEntry.contentHash().equals(candidate.contentHash())) {
                    if (!existingEntry.path().equals(candidate.path())) {
                        // 内容相同但路径变化时复用已有向量分块，仅更新索引路径。
                        documentIndexStore.upsertAll(List.of(candidate));
                        processedExistingDocumentIds.add(existingEntry.documentId());
                        updatedFiles++;
                        log.info(
                                "Document sync moved documentId={}",
                                candidate.documentId()
                        );
                    } else {
                        unchangedFiles++;
                    }
                    continue;
                }

                if (existingEntry != null) {
                    // 内容改变时先删除旧分块，再写入新内容，避免旧向量继续参与检索。
                    int removedChunks = removeIndexedDocument(existingEntry);
                    processedExistingDocumentIds.add(existingEntry.documentId());
                    deletedChunks += removedChunks;
                    updatedFiles++;
                    log.info(
                            "Document sync updating oldDocumentId={} newDocumentId={} deletedChunks={} addedChunks={}",
                            existingEntry.documentId(),
                            candidate.documentId(),
                            removedChunks,
                            ingestedFile.chunkCount()
                    );
                } else {
                    addedFiles++;
                    log.info("Document sync adding documentId={} addedChunks={}", candidate.documentId(), ingestedFile.chunkCount());
                }

                documents.removeIf(document -> document.documentId().equals(candidate.documentId()));
                documents.addAll(ingestedFile.documents());
                vectorStore.addAll(ingestedFile.documents());
                documentIndexStore.upsertAll(List.of(candidate));
                addedChunks += ingestedFile.chunkCount();
            }

            for (DocumentIndexEntry existingEntry : existingEntries) {
                if (scannedPaths.contains(existingEntry.path()) || processedExistingDocumentIds.contains(existingEntry.documentId())) {
                    continue;
                }

                int removedChunks = removeIndexedDocument(existingEntry);
                deletedFiles++;
                deletedChunks += removedChunks;
                log.info("Document sync deleting missing file documentId={} deletedChunks={}", existingEntry.documentId(), removedChunks);
            }

            long durationMs = System.currentTimeMillis() - startedAt;
            log.info(
                    "Document sync completed status=success scannedFiles={} addedFiles={} updatedFiles={} unchangedFiles={} deletedFiles={} addedChunks={} deletedChunks={} durationMs={}",
                    files.size(),
                    addedFiles,
                    updatedFiles,
                    unchangedFiles,
                    deletedFiles,
                    addedChunks,
                    deletedChunks,
                    durationMs
            );
            return new SyncResult(
                    "success",
                    files.size(),
                    addedFiles,
                    updatedFiles,
                    unchangedFiles,
                    deletedFiles,
                    addedChunks,
                    deletedChunks,
                    durationMs
            );
        } catch (RuntimeException exception) {
            log.error(
                    "Document sync failed status=failed scannedFiles={} durationMs={} errorType={}",
                    files.size(),
                    System.currentTimeMillis() - startedAt,
                    exception.getClass().getSimpleName()
            );
            throw exception;
        }
        } finally {
            ingestionLock.unlock();
        }
    }

    public SyncResult syncWorkspace(WorkspaceAccessContext access) throws IOException {
        return syncWorkspace(null, access);
    }

    public SyncResult syncWorkspace(String path, WorkspaceAccessContext access) throws IOException {
        ingestionLock.lock();
        try {
        ingestionPathResolver.requireWorkspaceWrite(access);
        long startedAt = System.currentTimeMillis();
        Path directoryPath = path == null || path.isBlank() ? ingestionPathResolver.workspaceDirectory(access) : ingestionPathResolver.resolveAllowedPath(path);
        if (Files.exists(directoryPath) && !Files.isDirectory(directoryPath)) {
            throw new IllegalArgumentException("Directory path must be a directory: " + path);
        }
        List<DocumentIndexEntry> existingEntries = documentIndexStore.list().stream()
                .filter(entry -> entry.workspaceId().equals(access.workspaceId()))
                .toList();
        List<Path> files = new ArrayList<>(supportedFiles(directoryPath));
        if (path == null || path.isBlank()) {
            existingEntries.stream()
                    .map(entry -> ingestionPathResolver.resolveIndexedPath(entry.path()))
                    .filter(Files::isRegularFile)
                    .filter(source -> !files.contains(source))
                    .forEach(files::add);
            files.sort(Path::compareTo);
        } else {
            existingEntries = existingEntries.stream()
                    .filter(entry -> ingestionPathResolver.resolveIndexedPath(entry.path()).startsWith(directoryPath))
                    .toList();
        }
        Set<String> scannedPaths = files.stream().map(ingestionPathResolver::workspaceRelativePath).collect(Collectors.toSet());
        Map<String, DocumentIndexEntry> entriesByPath = existingEntries.stream()
                .collect(Collectors.toMap(DocumentIndexEntry::path, entry -> entry, (left, right) -> left, HashMap::new));
        int addedFiles = 0;
        int updatedFiles = 0;
        int unchangedFiles = 0;
        int deletedFiles = 0;
        int addedChunks = 0;
        int deletedChunks = 0;

        for (Path file : files) {
            String relativePath = ingestionPathResolver.workspaceRelativePath(file);
            DocumentIndexEntry existing = entriesByPath.get(relativePath);
            String originalFileName = originalFileName(relativePath, access.workspaceId(),
                    existing == null ? file.getFileName().toString() : existing.fileName());
            IngestedFile candidate = ingestWorkspaceFile(file, originalFileName, access);
            if (candidate.indexEntry() == null) {
                if (existing != null) {
                    deletedChunks += removeIndexedDocument(existing);
                    deletedFiles++;
                }
                continue;
            }
            if (existing != null && existing.contentHash().equals(candidate.indexEntry().contentHash())) {
                unchangedFiles++;
                continue;
            }
            if (existing != null) {
                deletedChunks += removeIndexedDocument(existing);
                updatedFiles++;
            } else {
                addedFiles++;
            }
            documents.addAll(candidate.documents());
            vectorStore.addAll(candidate.documents());
            documentIndexStore.upsertAll(List.of(candidate.indexEntry()));
            addedChunks += candidate.chunkCount();
        }

        for (DocumentIndexEntry existing : existingEntries) {
            if (!scannedPaths.contains(existing.path())) {
                deletedChunks += removeIndexedDocument(existing);
                deletedFiles++;
            }
        }
        return new SyncResult("success", files.size(), addedFiles, updatedFiles, unchangedFiles,
                deletedFiles, addedChunks, deletedChunks, System.currentTimeMillis() - startedAt);
        } finally {
            ingestionLock.unlock();
        }
    }

    private List<String> chunkIds(DocumentIndexEntry entry) {
        List<String> ids = new ArrayList<>();

        for (int index = 0; index < entry.chunkCount(); index++) {
            ids.add(entry.documentId() + "#chunk-" + index);
        }

        return ids;
    }

    private int removeIndexedDocument(DocumentIndexEntry entry) {
        // 删除必须同时覆盖向量库、进程内列表和索引清单，三者缺一会造成检索不一致。
        List<String> ids = chunkIds(entry);
        vectorStore.deleteByIds(ids);
        documents.removeIf(document -> document.documentId().equals(entry.documentId()));
        documentIndexStore.delete(entry.documentId(), entry.workspaceId());
        return ids.size();
    }

    private void deleteWorkspaceUploadSource(DocumentIndexEntry entry) {
        String normalizedPath = entry.path().replace('\\', '/');
        String workspacePrefix = "docs/workspaces/" + entry.workspaceId() + "/";
        if (!normalizedPath.startsWith(workspacePrefix)) {
            return;
        }

        String storedFileName = normalizedPath.substring(workspacePrefix.length());
        if (!storedFileName.matches("[0-9a-fA-F-]{36}\\.(md|txt|pdf|docx|html|htm|png|jpg|jpeg)")) {
            // 只删除系统上传时生成的随机文件，手工维护或全局导入的源文件仍由管理员管理。
            return;
        }

        Path workspaceDirectory = docsDirectory.resolve("workspaces").resolve(entry.workspaceId()).normalize();
        Path sourcePath = ingestionPathResolver.resolveIndexedPath(entry.path());
        if (!workspaceDirectory.startsWith(docsDirectory) || !sourcePath.startsWith(workspaceDirectory)
                || !sourcePath.getParent().equals(workspaceDirectory)) {
            throw new IllegalStateException("Workspace document source path is outside its workspace directory");
        }

        try {
            if (!Files.exists(sourcePath, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                return;
            }
            Path realDocsDirectory = docsDirectory.toRealPath();
            Path realWorkspaceDirectory = workspaceDirectory.toRealPath();
            Path realSourcePath = sourcePath.toRealPath();
            if (!realWorkspaceDirectory.startsWith(realDocsDirectory)
                    || !realSourcePath.startsWith(realWorkspaceDirectory)
                    || !Files.isRegularFile(realSourcePath)) {
                throw new IllegalStateException("Workspace document source path is outside its workspace directory");
            }
            Files.delete(sourcePath);
            try {
                Files.delete(workspaceDirectory);
            } catch (IOException ignored) {
                // 空目录清理是尽力而为，不影响文档删除结果。
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to delete workspace document source", exception);
        }
    }

    private List<Path> supportedFiles(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return List.of();
        }

        try (var paths = Files.walk(directory)) {
            return paths.filter(Files::isRegularFile)
                    .filter(ingestionPathResolver::isIndexableDocument)
                    .sorted()
                    .toList();
        }
    }

    private IngestDocumentResult ingestPath(Path path, boolean force) {
        long startedAt = System.currentTimeMillis();
        if (!ingestionPathResolver.isIndexableDocument(path)) {
            log.info("Document ingest skipped reason=unsupported_document_type");
            return new IngestDocumentResult(
                    path.getFileName().toString(),
                    ingestionPathResolver.workspaceRelativePath(path),
                    null,
                    "failed",
                    0,
                    "Unsupported document type"
            );
        }

        // 解析不触碰共享状态，移出锁外，避免大文件解析期间阻塞其他摄入操作。
        IngestedFile ingestedFile = ingestFile(path);

        if (ingestedFile.chunkCount() == 0 || ingestedFile.indexEntry() == null) {
            log.info("Document ingest skipped reason=empty_document");
            return new IngestDocumentResult(
                    path.getFileName().toString(),
                    ingestionPathResolver.workspaceRelativePath(path),
                    null,
                    "skipped",
                    0,
                    "Document is empty"
            );
        }

        ingestionLock.lock();
        try {
            DocumentIndexEntry existingEntry = findExistingEntry(ingestedFile.indexEntry());
            if (!force && existingEntry != null && existingEntry.contentHash().equals(ingestedFile.indexEntry().contentHash())) {
                // 非强制导入遇到相同内容时跳过，避免重复分块和重复向量。
                log.info(
                        "Document ingest skipped reason=duplicate_document documentId={} chunks={}",
                        existingEntry.documentId(),
                        existingEntry.chunkCount()
                );
                return new IngestDocumentResult(
                        ingestedFile.indexEntry().fileName(),
                        ingestedFile.indexEntry().path(),
                        existingEntry.documentId(),
                        "skipped",
                        existingEntry.chunkCount(),
                        "Duplicate document skipped"
                );
            }

            if (existingEntry != null) {
                // 单文件更新按“删除旧版本，再导入新版本”处理。
                log.info(
                        "Document ingest replacing existing document oldDocumentId={} newDocumentId={}",
                        existingEntry.documentId(),
                        ingestedFile.indexEntry().documentId()
                );
                vectorStore.deleteByIds(chunkIds(existingEntry));
                documents.removeIf(document -> document.documentId().equals(existingEntry.documentId()));
                documentIndexStore.delete(existingEntry.documentId(), existingEntry.workspaceId());
            }

            documents.removeIf(document -> document.documentId().equals(ingestedFile.indexEntry().documentId()));
            documents.addAll(ingestedFile.documents());
            vectorStore.addAll(ingestedFile.documents());
            documentIndexStore.upsertAll(List.of(ingestedFile.indexEntry()));
        } finally {
            ingestionLock.unlock();
        }

        log.info(
                "Document ingest imported documentId={} chunks={} force={} durationMs={}",
                ingestedFile.indexEntry().documentId(),
                ingestedFile.chunkCount(),
                force,
                System.currentTimeMillis() - startedAt
        );
        return new IngestDocumentResult(
                ingestedFile.indexEntry().fileName(),
                ingestedFile.indexEntry().path(),
                ingestedFile.indexEntry().documentId(),
                "imported",
                ingestedFile.chunkCount(),
                force ? "Document re-imported" : "Document imported"
        );
    }

    private IngestDocumentResult ingestWorkspacePath(Path path, boolean force, WorkspaceAccessContext access) {
        return ingestWorkspacePath(path, force, access, path.getFileName().toString());
    }

    private IngestDocumentResult ingestWorkspacePath(
            Path path, boolean force, WorkspaceAccessContext access, String originalFileName) {
        if (!ingestionPathResolver.isIndexableDocument(path)) {
            return new IngestDocumentResult(path.getFileName().toString(), ingestionPathResolver.workspaceRelativePath(path), null,
                    "failed", 0, "Unsupported document type");
        }
        // 解析与文件拷贝不触碰共享状态，移出锁外，避免大文件解析期间阻塞其他摄入操作。
        IngestedFile sourceCandidate = ingestWorkspaceFile(path, originalFileName, access);
        if (sourceCandidate.indexEntry() == null) {
            return new IngestDocumentResult(originalFileName, ingestionPathResolver.workspaceRelativePath(path), null,
                    "skipped", 0, "Document is empty");
        }

        Path managedPath = path;
        Path workspaceDirectory = ingestionPathResolver.workspaceDirectory(access);
        if (!path.startsWith(workspaceDirectory)) {
            String extension = originalFileName.substring(originalFileName.lastIndexOf('.')).toLowerCase();
            managedPath = workspaceDirectory.resolve(UUID.randomUUID() + extension);
            try {
                Files.createDirectories(workspaceDirectory);
                Files.copy(path, managedPath);
            } catch (IOException exception) {
                throw new IllegalStateException("Failed to copy document into workspace", exception);
            }
        }

        IngestedFile ingested = ingestWorkspaceFile(managedPath, originalFileName, access);
        if (ingested.indexEntry() == null) {
            return new IngestDocumentResult(path.getFileName().toString(), ingestionPathResolver.workspaceRelativePath(path), null,
                    "skipped", 0, "Document is empty");
        }

        boolean deleteStaleSource = false;
        ingestionLock.lock();
        try {
            DocumentIndexEntry existing = findExistingWorkspaceEntry(ingested.indexEntry(), access.workspaceId());
            if (!force && existing != null && existing.contentHash().equals(ingested.indexEntry().contentHash())) {
                return new IngestDocumentResult(existing.fileName(), existing.path(), existing.documentId(),
                        "skipped", existing.chunkCount(), "Duplicate document skipped");
            }
            if (existing != null) {
                // 仅当路径变化时才需要清理旧受管源文件，文件删除放到锁外执行。
                deleteStaleSource = !existing.path().equals(ingested.indexEntry().path());
                removeIndexedDocument(existing);
            }
            documents.removeIf(document -> document.documentId().equals(ingested.indexEntry().documentId()));
            documents.addAll(ingested.documents());
            vectorStore.addAll(ingested.documents());
            documentIndexStore.upsertAll(List.of(ingested.indexEntry()));
        } finally {
            ingestionLock.unlock();
        }
        if (deleteStaleSource) {
            deleteWorkspaceUploadSource(/* existing */ findExistingWorkspaceEntry(ingested.indexEntry(), access.workspaceId()));
        }
        return new IngestDocumentResult(ingested.indexEntry().fileName(), ingested.indexEntry().path(),
                ingested.indexEntry().documentId(), "imported", ingested.chunkCount(),
                force ? "Document re-imported" : "Document imported");
    }

    private DocumentIndexEntry findExistingWorkspaceEntry(DocumentIndexEntry candidate, String workspaceId) {
        return documentIndexStore.list().stream()
                .filter(entry -> entry.workspaceId().equals(workspaceId))
                .filter(entry -> entry.path().equals(candidate.path())
                        || entry.contentHash().equals(candidate.contentHash())
                        || entry.documentId().equals(candidate.documentId()))
                .findFirst()
                .orElse(null);
    }

    private DocumentIndexEntry findExistingEntry(DocumentIndexEntry candidate) {
        return documentIndexStore.list().stream()
                .filter(entry -> entry.path().equals(candidate.path())
                        || entry.contentHash().equals(candidate.contentHash())
                        || entry.documentId().equals(candidate.documentId()))
                .findFirst()
                .orElse(null);
    }

    private IngestResponse responseFrom(List<IngestDocumentResult> results) {
        int imported = (int) results.stream().filter(result -> "imported".equals(result.status())).count();
        int skipped = (int) results.stream().filter(result -> "skipped".equals(result.status())).count();
        int failed = (int) results.stream().filter(result -> "failed".equals(result.status())).count();
        int documents = results.stream()
                .filter(result -> "imported".equals(result.status()))
                .mapToInt(IngestDocumentResult::chunks)
                .sum();

        return new IngestResponse(results.size(), documents, imported, skipped, failed, results);
    }

    private IngestedFile ingestFile(Path path) {
        try {
            String fileName = path.getFileName().toString();
            byte[] sourceContent = Files.readAllBytes(path);
            ParsedDocument parsedDocument = documentParserRouter.parse(fileName, sourceContent);
            String normalizedContent = parsedDocument.content();
            if (normalizedContent.isEmpty()) {
                return new IngestedFile(null, List.of());
            }

            // 内容哈希既用于去重，也作为稳定文档 ID 的来源；内容变化会得到新的 documentId。
            String contentHash = ingestionPathResolver.isBinaryDocument(fileName) ? ingestionPathResolver.sha256(sourceContent) : ingestionPathResolver.sha256(normalizedContent);
            String documentId = contentHash.substring(0, 16);
            String relativePath = ingestionPathResolver.workspaceRelativePath(path).replace('\\', '/');
            String category = ingestionPathResolver.documentCategory(relativePath);
            String ownerUserId = ingestionPathResolver.ownerUserId(relativePath);
            List<DocumentChunk> chunks = documentChunkerRouter.select(parsedDocument).chunk(parsedDocument);
            log.info(
                    "Document chunking completed chunks={} contentLength={}",
                    chunks.size(),
                    normalizedContent.length()
            );
            List<SourceDocument> sourceDocuments = new ArrayList<>();

            for (DocumentChunk documentChunk : chunks) {
                String id = documentId + "#chunk-" + documentChunk.chunkIndex();
                SourceDocument sourceDocument = new SourceDocument(
                        id,
                        parsedDocument.title(),
                        fileName,
                        relativePath,
                        documentId,
                        fileName,
                        contentHash,
                        documentChunk,
                        category,
                        ownerUserId
                );

                sourceDocuments.add(sourceDocument);
            }

            DocumentIndexEntry indexEntry = new DocumentIndexEntry(
                    documentId,
                    fileName,
                    relativePath,
                    contentHash,
                    chunks.size(),
                     java.time.Instant.now(),
                    category,
                    "INDEXED",
                    ownerUserId
            );

            return new IngestedFile(indexEntry, sourceDocuments);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to ingest document: " + path, exception);
        }
    }

    private IngestedFile ingestWorkspaceFile(Path path, String originalFileName, WorkspaceAccessContext access) {
        return ingestWorkspaceFile(path, originalFileName, access, (stage, progress) -> { });
    }

    private String originalFileName(String sourcePath, String workspaceId, String fallback) {
        if (documentTaskRepository == null) return fallback;
        return documentTaskRepository
                .findFirstBySourcePathAndWorkspaceIdAndTypeOrderByCreatedAtDesc(
                        sourcePath.replace('\\', '/'), workspaceId, DocumentTaskType.UPLOAD)
                .map(DocumentTaskEntity::getFileName)
                .filter(name -> name != null && !name.isBlank())
                .orElse(fallback);
    }

    private IngestedFile ingestWorkspaceFile(Path path, String originalFileName, WorkspaceAccessContext access,
                                              BiConsumer<String, Integer> progress) {
        return ingestWorkspaceFile(path, originalFileName, access, null, progress);
    }

    private IngestedFile ingestWorkspaceFile(Path path, String originalFileName, WorkspaceAccessContext access,
                                              String taskId, BiConsumer<String, Integer> progress) {
        try {
            byte[] sourceContent = Files.readAllBytes(path);
            progress.accept(ingestionPathResolver.isImageDocument(originalFileName) ? "OCR" : "PARSING", 25);
            DocumentParser parser = documentParserRouter.parserFor(originalFileName);
            boolean pdf = originalFileName.toLowerCase(java.util.Locale.ROOT).endsWith(".pdf");
            DocumentVisibility visibility = switch (access.type()) {
                case PERSONAL -> DocumentVisibility.PRIVATE;
                case TEAM -> DocumentVisibility.WORKSPACE;
                case ORG -> DocumentVisibility.PUBLIC;
                case PUBLIC -> DocumentVisibility.PUBLIC;
            };
            String contentHash = ingestionPathResolver.isBinaryDocument(originalFileName) ? ingestionPathResolver.sha256(sourceContent)
                    : ingestionPathResolver.sha256(sourceContent);
            String scopedHash = ingestionPathResolver.sha256(access.workspaceId() + "\n" + contentHash);
            String documentId = scopedHash.substring(0, 16);
            String relativePath = ingestionPathResolver.workspaceRelativePath(path).replace('\\', '/');
            Map<Integer, DocumentTaskBatchArtifactStore.SavedBatch> savedBatches = taskId == null || batchArtifactStore == null
                    ? new java.util.LinkedHashMap<>() : new java.util.LinkedHashMap<>(batchArtifactStore.load(taskId));
            int[] processedChunks = {savedBatches.values().stream().mapToInt(batch -> batch.chunks().size()).sum()};
            parser.parseEachBatch(sourceContent, pdf ? pdfBatchPages : 1,
                    batchIndex -> !savedBatches.containsKey(batchIndex), batch -> {
                        progress.accept("BATCH_START_" + batch.batchIndex() + "_OF_" + batch.totalBatches()
                                        + "_TOTALPAGES_" + batch.totalPages()
                                        + "_PAGES_" + batch.startPage() + "_" + batch.endPage(),
                                25 + (int) Math.floor((batch.batchIndex() - 1) * 50.0 / batch.totalBatches()));
                        List<DocumentChunk> batchChunks = documentChunkerRouter.select(batch.document())
                                .chunk(batch.document());
                        if (taskId != null && batchArtifactStore != null) {
                            batchArtifactStore.save(taskId, batch.batchIndex(), batch.document().title(), batchChunks);
                        }
                        savedBatches.put(batch.batchIndex(),
                                new DocumentTaskBatchArtifactStore.SavedBatch(batch.document().title(), batchChunks));
                        processedChunks[0] += batchChunks.size();
                        progress.accept("BATCH_" + batch.batchIndex() + "_OF_" + batch.totalBatches()
                                        + "_PAGES_" + batch.startPage() + "_" + batch.endPage()
                                        + "_CHUNKS_" + processedChunks[0],
                                25 + (int) Math.floor(batch.batchIndex() * 50.0 / batch.totalBatches()));
                    });
            List<DocumentChunk> chunks = new ArrayList<>();
            String title = savedBatches.values().stream().map(DocumentTaskBatchArtifactStore.SavedBatch::title)
                    .filter(value -> value != null && !value.isBlank()).findFirst().orElse(null);
            savedBatches.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
                for (DocumentChunk chunk : entry.getValue().chunks()) {
                    chunks.add(new DocumentChunk(chunk.content(), chunks.size(), chunk.headingPath(), chunk.headingLevel(),
                            chunk.startOffset(), chunk.endOffset(), chunk.chunkType(), chunk.pageNumber()));
                }
            });
            if (chunks.isEmpty()) return new IngestedFile(null, List.of());
            List<SourceDocument> sourceDocuments = chunks.stream().map(chunk -> new SourceDocument(
                    documentId + "#chunk-" + chunk.chunkIndex(), title, originalFileName, relativePath,
                    documentId, originalFileName, contentHash, chunk, "SOURCE", access.userId(),
                    access.workspaceId(), visibility)).toList();
            DocumentIndexEntry entry = new DocumentIndexEntry(
                     documentId, originalFileName, relativePath, scopedHash, chunks.size(), java.time.Instant.now(),
                    "SOURCE", "INDEXED", access.userId(), access.workspaceId(), visibility
            );
            return new IngestedFile(entry, sourceDocuments);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to ingest workspace document", exception);
        }
    }

    /**
     * 已持久化、等待异步解析的空间上传文件。
     */
    public record PendingWorkspaceUpload(String fileName, String sourcePath) {
    }

    private record IngestedFile(
            DocumentIndexEntry indexEntry,
            List<SourceDocument> documents
    ) {
        private int chunkCount() {
            return documents.size();
        }
    }
}
