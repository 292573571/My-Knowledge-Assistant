package com.example.workbench.rag;

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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class DocumentIngestionService {

    private static final Logger log = LoggerFactory.getLogger(DocumentIngestionService.class);
    private static final Path DEFAULT_DOCS_DIRECTORY = Path.of("docs");
    private static final Path WORKSPACE_DIRECTORY = Path.of("").toAbsolutePath().normalize();

    private final List<SourceDocument> documents = new ArrayList<>();
    private final VectorStore vectorStore;
    private final DocumentIndexStore documentIndexStore;
    private final DocumentChunkerRouter documentChunkerRouter;
    private final Path docsDirectory;

    @Autowired
    public DocumentIngestionService(
            VectorStore vectorStore,
            DocumentIndexStore documentIndexStore,
            DocumentChunkerRouter documentChunkerRouter
    ) {
        this(vectorStore, documentIndexStore, documentChunkerRouter, DEFAULT_DOCS_DIRECTORY);
    }

    DocumentIngestionService(
            VectorStore vectorStore,
            DocumentIndexStore documentIndexStore,
            DocumentChunkerRouter documentChunkerRouter,
            Path docsDirectory
    ) {
        this.vectorStore = vectorStore;
        this.documentIndexStore = documentIndexStore;
        this.documentChunkerRouter = documentChunkerRouter;
        this.docsDirectory = docsDirectory.toAbsolutePath().normalize();
    }

    public IngestResult ingestDocsDirectory() throws IOException {
        long startedAt = System.currentTimeMillis();
        if (!Files.exists(docsDirectory)) {
            log.info("Document ingest docs directory skipped reason=directory_not_found path={}", docsDirectory);
            return new IngestResult(0, 0);
        }

        log.info("Document ingest docs directory started path={}", docsDirectory);
        // 全量导入以磁盘 docs/ 为唯一事实来源，先重建进程内列表再整体替换向量库与索引。
        documents.clear();
        List<DocumentIndexEntry> indexEntries = new ArrayList<>();
        List<Path> supportedFiles;

        try (var paths = Files.walk(docsDirectory)) {
            supportedFiles = paths.filter(Files::isRegularFile)
                    .filter(this::isIndexableDocument)
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
        log.info("Document ingest file request started path={} force={}", path, force);
        // 所有外部传入路径都限制在 docs/ 内，防止 API 被用于读取工作区任意文件。
        Path documentPath = resolveAllowedPath(path);
        if (!Files.isRegularFile(documentPath)) {
            throw new IllegalArgumentException("Document path must be a file: " + path);
        }

        IngestDocumentResult result = ingestPath(documentPath, force);
        log.info(
                "Document ingest file request completed path={} status={} chunks={} durationMs={}",
                result.path(),
                result.status(),
                result.chunks(),
                System.currentTimeMillis() - startedAt
        );
        return responseFrom(List.of(result));
    }

    public IngestResponse ingestDirectory(String path, boolean force) throws IOException {
        long startedAt = System.currentTimeMillis();
        Path directoryPath = path == null || path.isBlank()
                ? docsDirectory
                : resolveAllowedPath(path);
        log.info("Document ingest directory request started path={} force={}", directoryPath, force);

        if (!Files.exists(directoryPath)) {
            log.info("Document ingest directory request skipped reason=directory_not_found path={}", directoryPath);
            return responseFrom(List.of());
        }

        if (!Files.isDirectory(directoryPath)) {
            throw new IllegalArgumentException("Directory path must be a directory: " + path);
        }

        List<Path> supportedFiles;
        try (var paths = Files.walk(directoryPath)) {
            supportedFiles = paths.filter(Files::isRegularFile)
                    .filter(this::isIndexableDocument)
                    .sorted()
                    .toList();
        }

        List<IngestDocumentResult> results = supportedFiles.stream()
                .map(file -> ingestPath(file, force))
                .toList();

        IngestResponse response = responseFrom(results);
        log.info(
                "Document ingest directory request completed path={} files={} imported={} skipped={} failed={} chunks={} durationMs={}",
                directoryPath,
                response.files(),
                response.imported(),
                response.skipped(),
                response.failed(),
                response.documents(),
                System.currentTimeMillis() - startedAt
        );
        return response;
    }

    public void ingest(SourceDocument document) {
        documents.add(document);
    }

    public List<SourceDocument> listDocuments() {
        return Collections.unmodifiableList(documents);
    }

    public List<DocumentIndexEntry> listIndexedDocuments() {
        return documentIndexStore.list().stream()
                .filter(entry -> !isLearningRecordEntry(entry))
                .toList();
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
        if (!entry.ownerUserId().isBlank() && !entry.ownerUserId().equals(ownerUserId)) {
            throw new IllegalArgumentException("Indexed document not found: " + documentId);
        }

        Path documentPath = resolveIndexedPath(entry.path());
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
                    Files.readString(realDocumentPath, StandardCharsets.UTF_8)
            );
        } catch (java.nio.file.NoSuchFileException exception) {
            throw new IllegalArgumentException("Document source file is not available: " + documentId);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to read document source: " + documentId, exception);
        }
    }

    public synchronized void deleteDocument(String documentId) {
        // 当前删除仅移除索引和向量数据，不删除 docs/ 下的源文件；重建时仍可重新导入。
        log.info("Document delete started documentId={}", documentId);
        DocumentIndexEntry entry = documentIndexStore.list().stream()
                .filter(item -> item.documentId().equals(documentId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Indexed document not found: " + documentId));
        removeIndexedDocument(entry);
        log.info("Document delete completed documentId={} remainingChunks={}", documentId, documents.size());
    }

    public synchronized void deleteIndexedPath(String path) {
        String normalizedPath = path.replace('\\', '/');
        documentIndexStore.list().stream()
                .filter(entry -> entry.path().replace('\\', '/').equals(normalizedPath))
                .findFirst()
                .ifPresent(this::removeIndexedDocument);
    }

    public synchronized RebuildResult rebuildDocuments() throws IOException {
        // 重建会清空全部索引后从 docs/ 重新导入，适用于向量库或索引不一致时恢复。
        long startedAt = System.currentTimeMillis();
        List<DocumentIndexEntry> indexedDocuments = documentIndexStore.list();
        List<String> indexedChunkIds = indexedDocuments.stream()
                .flatMap(entry -> chunkIds(entry).stream())
                .toList();
        log.info(
                "Document rebuild started stage=prepare_clear indexedDocuments={} indexedChunks={} docsPath={}",
                indexedDocuments.size(),
                indexedChunkIds.size(),
                docsDirectory
        );

        try {
            log.info("Document rebuild stage=clear_vector_store indexedChunks={}", indexedChunkIds.size());
            documents.clear();
            vectorStore.deleteByIds(indexedChunkIds);
            vectorStore.clear();
            documentIndexStore.clear();
            log.info("Document rebuild stage=clear_completed clearedDocuments={} clearedChunks={}", indexedDocuments.size(), indexedChunkIds.size());

            log.info("Document rebuild stage=ingest_started path={}", docsDirectory);
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
                    result.documents(),
                    durationMs
            );
        } catch (RuntimeException exception) {
            log.error(
                    "Document rebuild failed status=failed clearedDocuments={} clearedChunks={} durationMs={} errorType={} error={}",
                    indexedDocuments.size(),
                    indexedChunkIds.size(),
                    System.currentTimeMillis() - startedAt,
                    exception.getClass().getSimpleName(),
                    exception.getMessage(),
                    exception
            );
            throw exception;
        }
    }

    public synchronized SyncResult syncDocsDirectory() throws IOException {
        // 增量同步通过路径和内容哈希识别新增、修改、移动、删除和未变化文件。
        long startedAt = System.currentTimeMillis();
        List<DocumentIndexEntry> existingEntries = documentIndexStore.list();
        Map<String, DocumentIndexEntry> entriesByPath = existingEntries.stream()
                .collect(Collectors.toMap(DocumentIndexEntry::path, entry -> entry, (left, right) -> left, HashMap::new));
        Map<String, DocumentIndexEntry> entriesByHash = existingEntries.stream()
                .collect(Collectors.toMap(DocumentIndexEntry::contentHash, entry -> entry, (left, right) -> left, HashMap::new));
        Set<String> processedExistingDocumentIds = new java.util.HashSet<>();
        List<Path> files = supportedFiles(docsDirectory);
        Set<String> scannedPaths = files.stream().map(this::workspaceRelativePath).collect(Collectors.toSet());
        int addedFiles = 0;
        int updatedFiles = 0;
        int unchangedFiles = 0;
        int deletedFiles = 0;
        int addedChunks = 0;
        int deletedChunks = 0;

        log.info(
                "Document sync started stage=scan indexedDocuments={} scannedFiles={} docsPath={}",
                existingEntries.size(),
                files.size(),
                docsDirectory
        );

        try {
            for (Path path : files) {
                String relativePath = workspaceRelativePath(path);
                IngestedFile ingestedFile = ingestFile(path);
                DocumentIndexEntry existingEntry = entriesByPath.get(relativePath);

                if (ingestedFile.indexEntry() == null || ingestedFile.chunkCount() == 0) {
                    if (existingEntry != null) {
                        int removedChunks = removeIndexedDocument(existingEntry);
                        deletedFiles++;
                        deletedChunks += removedChunks;
                        log.info("Document sync removed empty file path={} deletedChunks={}", relativePath, removedChunks);
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
                                "Document sync moved path={} previousPath={} documentId={}",
                                candidate.path(),
                                existingEntry.path(),
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
                            "Document sync updating path={} oldDocumentId={} newDocumentId={} deletedChunks={} addedChunks={}",
                            relativePath,
                            existingEntry.documentId(),
                            candidate.documentId(),
                            removedChunks,
                            ingestedFile.chunkCount()
                    );
                } else {
                    addedFiles++;
                    log.info("Document sync adding path={} documentId={} addedChunks={}", relativePath, candidate.documentId(), ingestedFile.chunkCount());
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
                log.info("Document sync deleting missing file path={} documentId={} deletedChunks={}", existingEntry.path(), existingEntry.documentId(), removedChunks);
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
                    "Document sync failed status=failed scannedFiles={} durationMs={} errorType={} error={}",
                    files.size(),
                    System.currentTimeMillis() - startedAt,
                    exception.getClass().getSimpleName(),
                    exception.getMessage(),
                    exception
            );
            throw exception;
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
        documentIndexStore.delete(entry.documentId());
        return ids.size();
    }

    private List<Path> supportedFiles(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return List.of();
        }

        try (var paths = Files.walk(directory)) {
            return paths.filter(Files::isRegularFile)
                    .filter(this::isIndexableDocument)
                    .sorted()
                    .toList();
        }
    }

    private IngestDocumentResult ingestPath(Path path, boolean force) {
        long startedAt = System.currentTimeMillis();
        if (!isIndexableDocument(path)) {
            log.info("Document ingest skipped path={} reason=unsupported_document_type", workspaceRelativePath(path));
            return new IngestDocumentResult(
                    path.getFileName().toString(),
                    workspaceRelativePath(path),
                    null,
                    "failed",
                    0,
                    "Unsupported document type"
            );
        }

        IngestedFile ingestedFile = ingestFile(path);

        if (ingestedFile.chunkCount() == 0 || ingestedFile.indexEntry() == null) {
            log.info("Document ingest skipped path={} reason=empty_document", workspaceRelativePath(path));
            return new IngestDocumentResult(
                    path.getFileName().toString(),
                    workspaceRelativePath(path),
                    null,
                    "skipped",
                    0,
                    "Document is empty"
            );
        }

        DocumentIndexEntry existingEntry = findExistingEntry(ingestedFile.indexEntry());
        if (!force && existingEntry != null && existingEntry.contentHash().equals(ingestedFile.indexEntry().contentHash())) {
            // 非强制导入遇到相同内容时跳过，避免重复分块和重复向量。
            log.info(
                    "Document ingest skipped path={} reason=duplicate_document documentId={} chunks={}",
                    ingestedFile.indexEntry().path(),
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
                    "Document ingest replacing existing document oldDocumentId={} newDocumentId={} path={}",
                    existingEntry.documentId(),
                    ingestedFile.indexEntry().documentId(),
                    ingestedFile.indexEntry().path()
            );
            vectorStore.deleteByIds(chunkIds(existingEntry));
            documents.removeIf(document -> document.documentId().equals(existingEntry.documentId()));
            documentIndexStore.delete(existingEntry.documentId());
        }

        documents.removeIf(document -> document.documentId().equals(ingestedFile.indexEntry().documentId()));
        documents.addAll(ingestedFile.documents());
        vectorStore.addAll(ingestedFile.documents());
        documentIndexStore.upsertAll(List.of(ingestedFile.indexEntry()));

        log.info(
                "Document ingest imported path={} documentId={} chunks={} force={} durationMs={}",
                ingestedFile.indexEntry().path(),
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
            String content = Files.readString(path, StandardCharsets.UTF_8);
            String normalizedContent = content.trim();

            if (normalizedContent.isEmpty()) {
                return new IngestedFile(null, List.of());
            }

            String fileName = path.getFileName().toString();
            String title = extractTitle(normalizedContent);
            // 内容哈希既用于去重，也作为稳定文档 ID 的来源；内容变化会得到新的 documentId。
            String contentHash = sha256(normalizedContent);
            String documentId = contentHash.substring(0, 16);
            String relativePath = workspaceRelativePath(path).replace('\\', '/');
            String category = documentCategory(relativePath);
            String ownerUserId = ownerUserId(relativePath);
            List<DocumentChunk> chunks = documentChunkerRouter.select(fileName).chunk(normalizedContent);
            log.info(
                    "Document chunking completed path={} fileName={} chunks={} contentLength={} contentHash={}",
                    workspaceRelativePath(path),
                    fileName,
                    chunks.size(),
                    normalizedContent.length(),
                    contentHash
            );
            List<SourceDocument> sourceDocuments = new ArrayList<>();

            for (DocumentChunk documentChunk : chunks) {
                String id = documentId + "#chunk-" + documentChunk.chunkIndex();
                SourceDocument sourceDocument = new SourceDocument(
                        id,
                        title,
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
                    System.currentTimeMillis(),
                    category,
                    "INDEXED",
                    ownerUserId
            );

            return new IngestedFile(indexEntry, sourceDocuments);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to ingest document: " + path, exception);
        }
    }

    private Path resolveAllowedPath(String path) {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("path cannot be empty");
        }

        Path resolvedPath = Path.of(path).normalize();
        if (!resolvedPath.isAbsolute()) {
            resolvedPath = WORKSPACE_DIRECTORY.resolve(resolvedPath).normalize();
        }

        if (!resolvedPath.startsWith(docsDirectory)) {
            // normalize 后再校验前缀，防止通过 ../ 绕过 docs 目录限制。
            throw new IllegalArgumentException("Path must be under docs directory");
        }

        return resolvedPath;
    }

    private Path resolveIndexedPath(String indexedPath) {
        String normalizedPath = indexedPath.replace('\\', '/');
        Path resolvedPath = normalizedPath.startsWith("docs/")
                ? docsDirectory.resolve(normalizedPath.substring("docs/".length())).normalize()
                : Path.of(indexedPath).toAbsolutePath().normalize();
        if (!resolvedPath.startsWith(docsDirectory)) {
            throw new IllegalArgumentException("Indexed document path must be under docs directory");
        }
        return resolvedPath;
    }

    private String workspaceRelativePath(Path path) {
        Path normalizedPath = path.toAbsolutePath().normalize();
        if (normalizedPath.startsWith(docsDirectory)) {
            return "docs/" + docsDirectory.relativize(normalizedPath).toString();
        }

        if (normalizedPath.startsWith(WORKSPACE_DIRECTORY)) {
            return WORKSPACE_DIRECTORY.relativize(normalizedPath).toString();
        }

        return path.toString();
    }

    private boolean isSupportedDocument(Path path) {
        String fileName = path.getFileName().toString().toLowerCase();
        return fileName.endsWith(".md") || fileName.endsWith(".txt");
    }

    private boolean isIndexableDocument(Path path) {
        return isSupportedDocument(path)
                && !"LEARNING_RECORD".equals(documentCategory(workspaceRelativePath(path).replace('\\', '/')));
    }

    private String documentCategory(String path) {
        if (path.startsWith("docs/learning-records/")) {
            return "LEARNING_RECORD";
        }
        if (path.startsWith("docs/manual-notes/")) {
            return "FORMAL_NOTE";
        }
        return "SOURCE";
    }

    private String ownerUserId(String path) {
        if (!path.startsWith("docs/learning-records/") && !path.startsWith("docs/manual-notes/")) {
            return "";
        }
        var matcher = java.util.regex.Pattern.compile("/user-([^/]+)/").matcher(path);
        return matcher.find() ? matcher.group(1) : "";
    }

    private String extractTitle(String content) {
        return content.lines()
                .map(String::trim)
                .filter(line -> !line.isEmpty())
                .filter(line -> line.startsWith("# "))
                .map(line -> line.substring(2).trim())
                .findFirst()
                .orElse(null);
    }

    private String sha256(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
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
