package com.example.workbench.rag;

import com.example.workbench.workspace.DocumentVisibility;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@ConditionalOnProperty(name = "workbench.rag.hybrid.enabled", havingValue = "true", matchIfMissing = true)
/**
 * 基于 PostgreSQL 全文索引和应用层关键词覆盖率的稀疏检索器。
 */
public class PostgresSparseRetriever implements SparseRetriever {

    private static final Pattern TOKEN_PATTERN = Pattern.compile(
            "[A-Za-z][A-Za-z0-9_.:/+#-]{1,}|[A-Z0-9][A-Z0-9_.:/+#-]{1,}|[\\p{IsHan}]{2,}");
    private static final Set<String> STOP_WORDS = Set.of("什么", "如何", "为什么", "可以", "是否", "这个", "那个", "哪些", "以及");
    private final DocumentChunkRepository repository;

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * 创建 PostgreSQL 稀疏检索器。
     *
     * @param repository 文档分块仓库
     */
    public PostgresSparseRetriever(DocumentChunkRepository repository) {
        this.repository = repository;
    }

    /**
     * 增量写入文档分块。
     *
     * @param documents 待写入分块
     */
    @Transactional
    public void addAll(List<SourceDocument> documents) {
        if (documents.isEmpty()) {
            return;
        }
        repository.deleteAllByIdInBatch(documents.stream().map(SourceDocument::id).toList());
        repository.saveAll(documents.stream().map(DocumentChunkEntity::new).toList());
    }

    /**
     * 按分块标识删除索引。
     *
     * @param ids 分块标识列表
     */
    @Transactional
    public void deleteByIds(List<String> ids) {
        if (!ids.isEmpty()) {
            repository.deleteAllByIdInBatch(ids);
        }
    }

    /**
     * 使用给定分块整体替换稀疏索引。
     *
     * @param documents 完整分块集合
     */
    @Transactional
    public void replaceAll(List<SourceDocument> documents) {
        repository.deleteAllInBatch();
        repository.saveAll(documents.stream().map(DocumentChunkEntity::new).toList());
    }

    /**
     * 清空稀疏索引。
     */
    @Transactional
    public void clear() {
        repository.deleteAllInBatch();
    }

    @Override
    @Transactional(readOnly = true)
    public List<SourceDocument> search(String query, int topK, String ownerUserId, Set<String> readableWorkspaceIds) {
        List<String> terms = terms(query);
        if (terms.isEmpty() || topK <= 0) {
            return List.of();
        }

        boolean scoped = readableWorkspaceIds != null && !readableWorkspaceIds.isEmpty();
        StringBuilder sql = new StringBuilder("select c.* from document_chunks c where (")
                .append("c.visibility = 'PUBLIC'");
        if (ownerUserId != null && !ownerUserId.isBlank()) {
            sql.append(" or (c.visibility = 'PRIVATE' and c.owner_user_id = :ownerUserId");
            if (scoped) {
                sql.append(" and c.workspace_id in (:workspaceIds)");
            }
            sql.append(")");
        }
        if (scoped) {
            sql.append(" or (c.visibility = 'WORKSPACE' and c.workspace_id in (:workspaceIds))");
        }
        sql.append(") and (c.search_vector @@ websearch_to_tsquery('simple', :ftsQuery) or ");
        for (int index = 0; index < terms.size(); index++) {
            if (index > 0) {
                sql.append(" or ");
            }
            sql.append("position(lower(:term").append(index).append(") in lower(c.search_text)) > 0");
        }
        sql.append(") limit :candidateLimit");

        var nativeQuery = entityManager.createNativeQuery(sql.toString(), DocumentChunkEntity.class)
                .setParameter("candidateLimit", Math.max(topK * 8, 40));
        nativeQuery.setParameter("ftsQuery", String.join(" OR ", terms));
        if (ownerUserId != null && !ownerUserId.isBlank()) {
            nativeQuery.setParameter("ownerUserId", ownerUserId);
        }
        if (scoped) {
            nativeQuery.setParameter("workspaceIds", new ArrayList<>(readableWorkspaceIds));
        }
        for (int index = 0; index < terms.size(); index++) {
            nativeQuery.setParameter("term" + index, terms.get(index));
        }

        @SuppressWarnings("unchecked")
        List<DocumentChunkEntity> candidates = nativeQuery.getResultList();
        return candidates.stream()
                .map(entity -> new SparseMatch(entity, sparseScore(entity.searchableText(), query, terms)))
                .filter(match -> match.score() > 0)
                .sorted(Comparator.comparingDouble(SparseMatch::score).reversed()
                        .thenComparing(match -> match.entity().documentId())
                        .thenComparingInt(match -> match.entity().chunkIndex()))
                .limit(topK)
                .map(match -> match.entity().toSourceDocument(match.score()))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<SourceDocument> adjacent(String documentId, int chunkIndex, String ownerUserId, String workspaceId) {
        Set<String> readable = (workspaceId == null || workspaceId.isBlank()) ? null : Set.of(workspaceId);
        return repository.findByDocumentIdAndChunkIndexBetweenOrderByChunkIndex(documentId, chunkIndex - 1, chunkIndex + 1)
                .stream()
                .filter(entity -> entity.chunkIndex() != chunkIndex)
                .filter(entity -> visible(entity.toSourceDocument(0), ownerUserId, readable))
                .map(entity -> entity.toSourceDocument(0))
                .toList();
    }

    private List<String> terms(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        LinkedHashSet<String> terms = new LinkedHashSet<>();
        var matcher = TOKEN_PATTERN.matcher(query);
        while (matcher.find() && terms.size() < 12) {
            String term = matcher.group().toLowerCase(Locale.ROOT);
            if (!STOP_WORDS.contains(term)) {
                if (term.codePoints().allMatch(codePoint -> Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN)
                        && term.codePointCount(0, term.length()) > 2) {
                    List<Integer> codePoints = term.codePoints().boxed().toList();
                    for (int index = 0; index < codePoints.size() - 1 && terms.size() < 12; index++) {
                        terms.add(new String(new int[]{codePoints.get(index), codePoints.get(index + 1)}, 0, 2));
                    }
                } else {
                    terms.add(term);
                    addOcrIdentifierVariants(terms, term);
                }
            }
        }
        return new ArrayList<>(terms);
    }

    private void addOcrIdentifierVariants(LinkedHashSet<String> terms, String term) {
        if (!term.matches(".*[a-z].*") || !term.matches(".*[0-9].*")) {
            return;
        }
        terms.add(term.replace('1', 'i'));
        terms.add(term.replace('0', 'o'));
        terms.add(term.replace('5', 's'));
        terms.add(term.replace('8', 'b'));
    }

    private double sparseScore(String text, String query, List<String> terms) {
        String normalized = text.toLowerCase(Locale.ROOT);
        String normalizedQuery = query == null ? "" : query.strip().toLowerCase(Locale.ROOT);
        double score = !normalizedQuery.isBlank() && normalized.contains(normalizedQuery) ? 4.0 : 0.0;
        int matched = 0;
        for (String term : terms) {
            if (normalized.contains(term)) {
                matched++;
                score += term.matches(".*[a-z0-9].*") ? 2.0 : 1.0;
            }
        }
        return score + (terms.isEmpty() ? 0 : (double) matched / terms.size());
    }

    private boolean visible(SourceDocument source, String ownerUserId, Set<String> readableWorkspaceIds) {
        if (source.visibility() == DocumentVisibility.PUBLIC) {
            return true;
        }
        if (source.visibility() == DocumentVisibility.PRIVATE) {
            return ownerUserId != null && !ownerUserId.isBlank() && ownerUserId.equals(source.ownerUserId())
                    && (readableWorkspaceIds == null || readableWorkspaceIds.contains(source.workspaceId()));
        }
        return readableWorkspaceIds != null && readableWorkspaceIds.contains(source.workspaceId());
    }

    private record SparseMatch(DocumentChunkEntity entity, double score) {
    }
}
