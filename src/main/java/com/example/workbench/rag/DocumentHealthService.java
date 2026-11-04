package com.example.workbench.rag;

import java.nio.file.Files;
import java.nio.file.Path;
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
 * 孤儿索引（声称有分块但分块表没有行）、源文件丢失（重导入/重建依赖源文件）。</p>
 */
@Service
public class DocumentHealthService {

    private static final Logger log = LoggerFactory.getLogger(DocumentHealthService.class);
    private static final int MAX_DUPLICATE_GROUPS = 20;
    private static final int MAX_ISSUES = 50;

    private final DocumentIndexRepository indexRepository;
    private final DocumentChunkRepository chunkRepository;
    private final IngestionPathResolver pathResolver;

    public DocumentHealthService(DocumentIndexRepository indexRepository,
                                 DocumentChunkRepository chunkRepository,
                                 IngestionPathResolver pathResolver) {
        this.indexRepository = indexRepository;
        this.chunkRepository = chunkRepository;
        this.pathResolver = pathResolver;
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
}
