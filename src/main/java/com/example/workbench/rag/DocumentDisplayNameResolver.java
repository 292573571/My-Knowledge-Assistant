package com.example.workbench.rag;

import java.util.AbstractMap;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 统一解析面向用户展示的文档名称。
 *
 * <p>物理存储路径可以使用随机名称，但展示名称必须来自原始上传文件名。所有引用、历史消息
 * 和其它面向用户的文档摘要都应复用本组件，避免不同链路各自猜测文件名。</p>
 */
@Component
public class DocumentDisplayNameResolver {

    private final DocumentTaskRepository taskRepository;

    @Autowired
    public DocumentDisplayNameResolver(DocumentTaskRepository taskRepository) {
        this.taskRepository = taskRepository;
    }

    public String resolve(SourceDocument source, List<DocumentIndexEntry> indexedDocuments) {
        if (source == null) return "知识库文档";
        Set<String> identifiers = Stream.of(source.path(), source.source(), source.fileName())
                .map(this::normalize)
                .filter(value -> !value.isBlank())
                .map(this::baseName)
                .collect(Collectors.toSet());
        String matched = indexedDocuments == null ? null : indexedDocuments.stream()
                .filter(entry -> !isGeneratedName(entry.fileName()))
                .map(entry -> new AbstractMap.SimpleImmutableEntry<>(entry, score(source, entry, identifiers)))
                .filter(candidate -> candidate.getValue() > 0)
                .sorted(Comparator.<Map.Entry<DocumentIndexEntry, Integer>>comparingInt(Map.Entry::getValue)
                        .reversed()
                        .thenComparing(candidate -> candidate.getKey().ingestedAt(), Comparator.reverseOrder()))
                .map(candidate -> candidate.getKey().fileName())
                .findFirst()
                .orElse(null);
        if (matched != null && !matched.isBlank()) return matched;

        String taskName = taskRepository.findFirstBySourcePathAndWorkspaceIdAndTypeOrderByCreatedAtDesc(
                        normalize(source.path()), source.workspaceId(), DocumentTaskType.UPLOAD)
                .map(DocumentTaskEntity::getFileName)
                .filter(name -> !isGeneratedName(name))
                .orElse(null);
        if (taskName != null) return taskName;

        return firstHumanName(source.title(), source.fileName(), source.source(), source.path());
    }

    public String resolve(String file, String path, String workspaceId, List<DocumentIndexEntry> indexedDocuments) {
        SourceDocument source = new SourceDocument("history-source", "", "", file, path, 0,
                "", file, "", 0, "", 0, 0, 0, "history", "SOURCE", "", workspaceId,
                com.example.workbench.workspace.DocumentVisibility.WORKSPACE, 0);
        return resolve(source, indexedDocuments);
    }

    private int score(SourceDocument source, DocumentIndexEntry entry, Set<String> identifiers) {
        String entryPath = normalize(entry.path());
        if (!normalize(source.path()).isBlank() && entryPath.equals(normalize(source.path()))) return 100;
        if (identifiers.contains(baseName(entryPath))) return 90;
        if (!source.documentId().isBlank() && entry.documentId().equals(source.documentId())) return 80;
        if (!source.contentHash().isBlank() && entry.contentHash().equals(source.contentHash())) return 70;
        if (sameGeneratedStem(identifiers, baseName(entryPath))) return 60;
        return 0;
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

    private String baseName(String value) {
        int slash = value.lastIndexOf('/');
        return slash < 0 ? value : value.substring(slash + 1);
    }
}
