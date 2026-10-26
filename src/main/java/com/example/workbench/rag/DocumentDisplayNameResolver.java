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
        return resolveWithTasks(source, indexedDocuments, lookupTasks(value(source.workspaceId())));
    }

    /**
     * 预先加载某个工作空间的上传任务快照。
     *
     * <p>会话历史里每条消息的每个来源都需要任务表才能还原展示名，逐条调用
     * {@link #resolve(String, String, String, List)} 会把同一份任务列表重复查 N 次。
     * 调用方可先用本方法取一次快照，再把它透传给批量解析。</p>
     *
     * @param workspaceId 工作空间标识
     * @return 该工作空间的任务快照，可直接传给带 {@link TaskScope} 参数的重载
     */
    public TaskScope scope(String workspaceId) {
        String normalized = value(workspaceId);
        return new TaskScope(normalized, lookupTasks(normalized));
    }

    private List<DocumentTaskEntity> lookupTasks(String workspaceId) {
        return taskRepository.findByWorkspaceIdAndTypeOrderByCreatedAtDesc(workspaceId, DocumentTaskType.UPLOAD);
    }

    /**
     * 一次查询得到的任务快照，用于在单次请求内复用。
     *
     * <p>快照与工作空间绑定：请求的工作空间与快照不一致时回退为独立查询，
     * 避免把一个空间的任务名错配到另一个空间的引用上。</p>
     */
    public static final class TaskScope {

        private final String workspaceId;
        private final List<DocumentTaskEntity> tasks;

        private TaskScope(String workspaceId, List<DocumentTaskEntity> tasks) {
            this.workspaceId = workspaceId;
            this.tasks = tasks == null ? List.of() : tasks;
        }

        private boolean matches(String targetWorkspaceId) {
            return workspaceId.equals(targetWorkspaceId == null ? "" : targetWorkspaceId.strip());
        }
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
        return resolve(file, path, workspaceId, indexedDocuments, null);
    }

    /**
     * 解析历史引用里保存的文件展示名，可复用调用方预先取好的任务快照。
     *
     * @param file            引用里的文件名
     * @param path            引用里的存储路径
     * @param workspaceId     所属工作空间
     * @param indexedDocuments 已加载的文档索引(整个请求共用一份)
     * @param scope           任务快照；为空时按需查询
     * @return 面向用户的展示名
     */
    public String resolve(String file, String path, String workspaceId, List<DocumentIndexEntry> indexedDocuments,
            TaskScope scope) {
        SourceDocument source = new SourceDocument("history-source", "", "", file, path, 0,
                "", file, "", 0, "", 0, 0, 0, "history", "SOURCE", "", workspaceId,
                com.example.workbench.workspace.DocumentVisibility.WORKSPACE, 0);
        String normalizedWorkspace = value(workspaceId);
        List<DocumentTaskEntity> tasks = scope != null && scope.matches(normalizedWorkspace)
                ? scope.tasks
                : lookupTasks(normalizedWorkspace);
        return resolveWithTasks(source, indexedDocuments, tasks);
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
