package com.example.workbench.rag;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 知识库健康度报告（管理员口径，全系统）。
 *
 * <p>盘点四类问题：索引状态分布、跨空间重复文档（同内容哈希）、
 * 孤儿索引（声称有分块但分块表没有行）、源文件丢失（重导入/重建依赖源文件）。
 * 同时提供孤儿索引与重复文档的一键清理：删除动作覆盖向量库、索引清单和分块表三处，
 * 与 {@link DocumentIngestionService#deleteDocument} 的删除语义保持一致。</p>
 */
@Service
public class DocumentHealthService {

    private static final Logger log = LoggerFactory.getLogger(DocumentHealthService.class);
    private static final int MAX_DUPLICATE_GROUPS = 20;
    private static final int MAX_ISSUES = 50;

    private final DocumentIndexRepository indexRepository;
    private final DocumentChunkRepository chunkRepository;
    private final IngestionPathResolver pathResolver;
    private final VectorStore vectorStore;
    private final DocumentIndexStore indexStore;

    public DocumentHealthService(DocumentIndexRepository indexRepository,
                                 DocumentChunkRepository chunkRepository,
                                 IngestionPathResolver pathResolver,
                                 VectorStore vectorStore,
                                 DocumentIndexStore indexStore) {
        this.indexRepository = indexRepository;
        this.chunkRepository = chunkRepository;
        this.pathResolver = pathResolver;
        this.vectorStore = vectorStore;
        this.indexStore = indexStore;
    }

    @Transactional(readOnly = true)
    public DocumentHealthResponse report() {
        List<DocumentIndexEntry> documents = indexRepository.findAllByOrderByFileNameAsc().stream()
                .map(DocumentIndexEntity::toEntry)
                .toList();

        Map<String, Long> chunkRowsByDocument = new HashMap<>();
        for (Object[] row : chunkRepository.countGroupedByDocumentId()) {
            chunkRowsByDocument.put((String) row[0], (Long) row[1]);
        }

        Map<String, Long> byStatus = documents.stream()
                .map(DocumentIndexEntry::indexStatus)
                .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
        Map<String, Long> byCategory = documents.stream()
                .map(DocumentIndexEntry::category)
                .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));

        List<DuplicateGroup> duplicates = documents.stream()
                .collect(Collectors.groupingBy(DocumentIndexEntry::contentHash))
                .entrySet().stream()
                .filter(group -> group.getValue().size() > 1)
                .map(group -> new DuplicateGroup(group.getKey(), group.getValue().size(),
                        group.getValue().stream()
                                .map(entry -> new DocumentRef(entry.documentId(), entry.fileName(), entry.workspaceId()))
                                .toList()))
                .sorted(Comparator.comparingInt(DuplicateGroup::count).reversed()
                        .thenComparing(group -> group.contentHash()))
                .limit(MAX_DUPLICATE_GROUPS)
                .toList();

        List<DocumentIssue> orphanIndexes = documents.stream()
                .filter(entry -> entry.chunkCount() > 0)
                .filter(entry -> chunkRowsByDocument.getOrDefault(entry.documentId(), 0L) == 0L)
                .limit(MAX_ISSUES)
                .map(this::toIssue)
                .toList();

        List<DocumentIssue> missingSources = new java.util.ArrayList<>();
        for (DocumentIndexEntry entry : documents) {
            if (missingSources.size() >= MAX_ISSUES) {
                break;
            }
            try {
                Path resolved = pathResolver.resolveIndexedPath(entry.path());
                if (!Files.exists(resolved)) {
                    missingSources.add(new DocumentIssue(entry.documentId(), entry.fileName(), entry.workspaceId(),
                            entry.chunkCount(), "源文件不存在: " + entry.path()));
                }
            } catch (RuntimeException exception) {
                log.warn("Document health source path check failed path={}", entry.path(), exception);
                missingSources.add(new DocumentIssue(entry.documentId(), entry.fileName(), entry.workspaceId(),
                        entry.chunkCount(), "源文件路径非法: " + entry.path()));
            }
        }

        long totalChunks = chunkRowsByDocument.values().stream().mapToLong(Long::longValue).sum();
        return new DocumentHealthResponse(documents.size(), totalChunks,
                sortByCountDesc(byStatus), sortByCountDesc(byCategory),
                duplicates, orphanIndexes, missingSources);
    }

    private Map<String, Long> sortByCountDesc(Map<String, Long> counts) {
        return counts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue,
                        (left, right) -> left, LinkedHashMap::new));
    }

    /**
     * 预览清理计划（不做任何删除）。scope 取 orphan（孤儿索引）或 duplicate（重复文档）。
     */
    public CleanupResult planCleanup(String scope) {
        if ("orphan".equals(scope)) {
            List<DocumentRef> removals = orphans().stream().map(this::toRef).toList();
            return new CleanupResult(scope, true, removals.size(), removals, List.of());
        }
        if ("duplicate".equals(scope)) {
            DuplicatePlan plan = duplicatePlan();
            return new CleanupResult(scope, true, plan.removals().size(), plan.removals(), plan.keeps());
        }
        throw new IllegalArgumentException("未知清理范围: " + scope);
    }

    /** 清理全部孤儿索引：向量库残留分块 + 索引清单行 + 分块表兜底删除。 */
    @Transactional
    public CleanupResult cleanupOrphans() {
        List<DocumentIndexEntry> targets = orphans();
        for (DocumentIndexEntry entry : targets) {
            purge(entry);
        }
        log.info("Document health orphan cleanup completed removed={}", targets.size());
        return new CleanupResult("orphan", false, targets.size(),
                targets.stream().map(this::toRef).toList(), List.of());
    }

    /**
     * 清理重复文档：每个同内容哈希分组只保留分块最全的一份（并列取文件名靠前者），其余删除。
     * 重复必然跨空间（同空间上传时已按 contentHash 去重），删除的是其他空间里的多余副本。
     */
    @Transactional
    public CleanupResult cleanupDuplicates() {
        DuplicatePlan plan = duplicatePlan();
        for (DocumentIndexEntry entry : plan.removalEntries()) {
            purge(entry);
        }
        log.info("Document health duplicate cleanup completed removed={} kept={}",
                plan.removals().size(), plan.keeps().size());
        return new CleanupResult("duplicate", false, plan.removals().size(), plan.removals(), plan.keeps());
    }

    private List<DocumentIndexEntry> allDocuments() {
        return indexRepository.findAllByOrderByFileNameAsc().stream()
                .map(DocumentIndexEntity::toEntry)
                .toList();
    }

    private Map<String, Long> chunkRowsByDocument() {
        Map<String, Long> chunkRows = new HashMap<>();
        for (Object[] row : chunkRepository.countGroupedByDocumentId()) {
            chunkRows.put((String) row[0], (Long) row[1]);
        }
        return chunkRows;
    }

    private List<DocumentIndexEntry> orphans() {
        Map<String, Long> chunkRows = chunkRowsByDocument();
        return allDocuments().stream()
                .filter(entry -> entry.chunkCount() > 0)
                .filter(entry -> chunkRows.getOrDefault(entry.documentId(), 0L) == 0L)
                .toList();
    }

    private DuplicatePlan duplicatePlan() {
        List<DocumentRef> removals = new ArrayList<>();
        List<DocumentRef> keeps = new ArrayList<>();
        List<DocumentIndexEntry> removalEntries = new ArrayList<>();
        allDocuments().stream()
                .collect(Collectors.groupingBy(DocumentIndexEntry::contentHash, LinkedHashMap::new, Collectors.toList()))
                .values().stream()
                .filter(group -> group.size() > 1)
                .forEach(group -> {
                    DocumentIndexEntry keep = keepEntry(group);
                    keeps.add(toRef(keep));
                    group.stream().filter(entry -> entry != keep).forEach(entry -> {
                        removals.add(toRef(entry));
                        removalEntries.add(entry);
                    });
                });
        return new DuplicatePlan(removals, keeps, removalEntries);
    }

    /** 保留分块最全的一份；并列时保持 fileName 升序的第一份（严格大于才替换），保证结果可复现。 */
    private DocumentIndexEntry keepEntry(List<DocumentIndexEntry> group) {
        DocumentIndexEntry keep = group.get(0);
        for (DocumentIndexEntry candidate : group) {
            if (candidate.chunkCount() > keep.chunkCount()) {
                keep = candidate;
            }
        }
        return keep;
    }

    private void purge(DocumentIndexEntry entry) {
        List<String> ids = new ArrayList<>();
        for (int index = 0; index < entry.chunkCount(); index++) {
            ids.add(entry.documentId() + "#chunk-" + index);
        }
        vectorStore.deleteByIds(ids);
        indexStore.delete(entry.documentId(), entry.workspaceId());
        chunkRepository.deleteByDocumentId(entry.documentId());
        log.info("Document health purge documentId={} file={} vectors={}",
                entry.documentId(), entry.fileName(), ids.size());
    }

    private DocumentRef toRef(DocumentIndexEntry entry) {
        return new DocumentRef(entry.documentId(), entry.fileName(), entry.workspaceId());
    }

    private DocumentIssue toIssue(DocumentIndexEntry entry) {
        return new DocumentIssue(entry.documentId(), entry.fileName(), entry.workspaceId(),
                entry.chunkCount(), "索引存在但分块表无记录");
    }

    public record DocumentHealthResponse(
            int totalDocuments,
            long totalChunks,
            Map<String, Long> byStatus,
            Map<String, Long> byCategory,
            List<DuplicateGroup> duplicates,
            List<DocumentIssue> orphanIndexes,
            List<DocumentIssue> missingSources) {
    }

    public record DuplicateGroup(String contentHash, int count, List<DocumentRef> documents) {
    }

    public record DocumentRef(String documentId, String fileName, String workspaceId) {
    }

    public record DocumentIssue(String documentId, String fileName, String workspaceId,
                                int chunkCount, String detail) {
    }

    /** 清理结果：dryRun=true 时 removed/kept 为计划清单，未发生删除。 */
    public record CleanupResult(String scope, boolean dryRun, int removedCount,
                                List<DocumentRef> removed, List<DocumentRef> kept) {
    }

    private record DuplicatePlan(List<DocumentRef> removals, List<DocumentRef> keeps,
                                 List<DocumentIndexEntry> removalEntries) {
    }
}
