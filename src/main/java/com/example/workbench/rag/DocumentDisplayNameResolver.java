package com.example.workbench.rag;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 统一解析面向用户展示的文档名称。
 *
 * <p>物理存储路径可以使用随机名称，但展示名称必须来自原始上传文件名。所有引用、历史消息
 * 和其它面向用户的文档摘要都应复用本组件，避免不同链路各自猜测文件名。</p>
 */
@Component
public class DocumentDisplayNameResolver {

    private static final Logger log = LoggerFactory.getLogger(DocumentDisplayNameResolver.class);
    private final DocumentTaskRepository taskRepository;

    @Autowired
    public DocumentDisplayNameResolver(DocumentTaskRepository taskRepository) {
        this.taskRepository = taskRepository;
    }

    public String resolve(SourceDocument source, List<DocumentIndexEntry> indexedDocuments) {
        String workspaceId = value(source.workspaceId());
        List<DocumentTaskEntity> tasks = taskRepository.findByWorkspaceIdAndTypeOrderByCreatedAtDesc(
                workspaceId, DocumentTaskType.UPLOAD);
        return resolveWithTasks(source, indexedDocuments, tasks);
    }

    /**
     * 批量解析多个来源的展示名。同一 workspace 的任务列表只查询一次(消除逐来源 N+1),
     * 后续每个来源在内存中匹配。返回顺序与输入 {@code sources} 一致。
     */
    public List<String> resolveMany(List<SourceDocument> sources, List<DocumentIndexEntry> indexedDocuments) {
        if (sources == null || sources.isEmpty()) {
            return List.of();
        }
        Map<String, List<DocumentTaskEntity>> tasksByWorkspace = new HashMap<>();
        List<String> result = new ArrayList<>(sources.size());
        for (SourceDocument source : sources) {
            String workspaceId = value(source.workspaceId());
            List<DocumentTaskEntity> tasks = tasksByWorkspace.computeIfAbsent(workspaceId,
                    key -> taskRepository.findByWorkspaceIdAndTypeOrderByCreatedAtDesc(key, DocumentTaskType.UPLOAD));
            result.add(resolveWithTasks(source, indexedDocuments, tasks));
        }
        return result;
    }

    private String resolveWithTasks(SourceDocument source, List<DocumentIndexEntry> indexedDocuments,
            List<DocumentTaskEntity> tasks) {
        if (source == null) return "知识库文档";
        String workspaceId = value(source.workspaceId());
        boolean scopedWorkspace = !workspaceId.isBlank() && !"public-default".equals(workspaceId);
        List<DocumentIndexEntry> entries = (indexedDocuments == null ? List.<DocumentIndexEntry>of() : indexedDocuments).stream()
                .filter(entry -> !scopedWorkspace || workspaceId.equals(value(entry.workspaceId())))
                .toList();
        Set<String> identifiers = Stream.of(source.path(), source.source(), source.fileName(), source.title())
                .map(this::normalize)
                .filter(value -> !value.isBlank())
                .map(this::baseName)
                .map(value -> value.toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());
        String matched = entries.stream()
                .map(entry -> new AbstractMap.SimpleImmutableEntry<>(entry, score(source, entry, identifiers)))
                .filter(candidate -> candidate.getValue() > 0 && !isGeneratedName(candidate.getKey().fileName()))
                .sorted(Comparator.<Map.Entry<DocumentIndexEntry, Integer>>comparingInt(Map.Entry::getValue)
                        .reversed()
                        .thenComparing(candidate -> candidate.getKey().ingestedAt(), Comparator.reverseOrder()))
                .map(candidate -> candidate.getKey().fileName())
                .findFirst()
                .orElse(null);
        if (matched != null && !matched.isBlank()) return matched;

        String documentId = value(source.documentId());
        if (!documentId.isBlank()) {
            String taskName = tasks.stream()
                    .filter(task -> documentId.equals(task.getDocumentId()))
                    .filter(task -> !isGeneratedName(task.getFileName()))
                    .max(Comparator.comparing(task -> task.getCreatedAt() == null ? java.time.Instant.EPOCH : task.getCreatedAt()))
                    .map(DocumentTaskEntity::getFileName)
                    .orElse(null);
            if (taskName != null) return taskName;
        }

        String taskName = tasks.stream()
                .filter(task -> normalize(source.path()).equals(normalize(task.getSourcePath())))
                .filter(task -> !isGeneratedName(task.getFileName()))
                .max(Comparator.comparing(task -> task.getCreatedAt() == null ? java.time.Instant.EPOCH : task.getCreatedAt()))
                .map(DocumentTaskEntity::getFileName)
                .orElse(null);
        if (taskName != null) return taskName;

        String taskByStorageName = tasks.stream()
                .filter(task -> sameStorageFile(source, task.getSourcePath()))
                .map(DocumentTaskEntity::getFileName)
                .filter(name -> !isGeneratedName(name))
                .findFirst()
                .orElse(null);
        if (taskByStorageName != null) return taskByStorageName;

        String fallback = firstHumanName(source.title(), source.fileName(), source.source(), source.path());
        if (isGeneratedName(fallback)) {
            log.warn("Unable to resolve generated citation name documentId={} workspaceId={} file={} source={} path={} indexedEntries={}",
                    documentId, workspaceId, source.fileName(), source.source(), source.path(), entries.size());
            return "知识库文档";
        }
        return fallback;
    }

    public String resolve(String file, String path, String workspaceId, List<DocumentIndexEntry> indexedDocuments) {
        SourceDocument source = new SourceDocument("history-source", "", "", file, path, 0,
                "", file, "", 0, "", 0, 0, 0, "history", "SOURCE", "", workspaceId,
                com.example.workbench.workspace.DocumentVisibility.WORKSPACE, 0);
        return resolve(source, indexedDocuments);
    }

    private int score(SourceDocument source, DocumentIndexEntry entry, Set<String> identifiers) {
        String entryPath = normalize(entry.path());
        String sourcePath = normalize(source.path());
        if (!value(source.documentId()).isBlank() && value(entry.documentId()).equals(source.documentId())) return 140;
        if (!value(source.contentHash()).isBlank() && value(entry.contentHash()).equals(source.contentHash())) return 130;
        if (!sourcePath.isBlank() && entryPath.equalsIgnoreCase(sourcePath)) return 120;
        if (!sourcePath.isBlank() && pathEquivalent(sourcePath, entryPath)) return 115;
        if (identifiers.contains(baseName(entryPath).toLowerCase(Locale.ROOT))) return 110;
        if (sameGeneratedStem(identifiers, baseName(entryPath))) return 100;
        return 0;
    }

    private boolean pathEquivalent(String left, String right) {
        String normalizedLeft = left.toLowerCase(Locale.ROOT);
        String normalizedRight = right.toLowerCase(Locale.ROOT);
        return normalizedLeft.endsWith("/" + normalizedRight)
                || normalizedRight.endsWith("/" + normalizedLeft);
    }

    private boolean sameStorageFile(SourceDocument source, String taskPath) {
        String taskFile = baseName(normalize(taskPath));
        if (taskFile.isBlank()) return false;
        return Stream.of(source.path(), source.source(), source.fileName())
                .map(value -> baseName(normalize(value)))
                .anyMatch(value -> !value.isBlank() && value.equalsIgnoreCase(taskFile));
    }

    private boolean sameGeneratedStem(Set<String> identifiers, String candidate) {
        if (!isGeneratedName(candidate)) return false;
        String candidateStem = stem(candidate);
        return identifiers.stream().anyMatch(value -> isGeneratedName(value) && stem(value).equalsIgnoreCase(candidateStem));
    }

    private String firstHumanName(String... values) {
        return Stream.of(values)
                .map(this::normalize)
                .filter(value -> !value.isBlank() && !isGeneratedName(baseName(value)))
                .map(this::baseName)
                .findFirst()
                .orElse("知识库文档");
    }

    private boolean isGeneratedName(String value) {
        if (value == null || value.isBlank()) return false;
        String name = baseName(normalize(value));
        try {
            String stem = name.substring(0, name.lastIndexOf('.'));
            UUID.fromString(stem);
            return true;
        } catch (RuntimeException ignored) {
            return name.matches("(?i)[0-9a-f]{16}\\.[a-z0-9]+") || name.matches("(?i)[0-9a-f]{32,}\\.[a-z0-9]+");
        }
    }

    private String stem(String value) {
        String name = baseName(normalize(value));
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private String normalize(String value) {
        return value == null ? "" : value.replace('\\', '/').strip();
    }

    private String value(String value) {
        return value == null ? "" : value.strip();
    }

    private String baseName(String value) {
        int slash = value.lastIndexOf('/');
        return slash < 0 ? value : value.substring(slash + 1);
    }
}
