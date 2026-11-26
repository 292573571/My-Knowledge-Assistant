package com.example.workbench.rag;

import com.example.workbench.workspace.DocumentVisibility;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ai.embedding.EmbeddingModel;

/**
 * 基于 PostgreSQL pgvector 的持久向量存储实现。
 *
 * <p>与 {@link PostgresSparseRetriever} 共用 document_chunks 表：向量与切片正文同表，
 * 写入在同一事务内原子生效 —— 这是相对 Chroma（独立进程、无事务耦合）的核心收益。</p>
 *
 * <p>向量以 {@code '[..]'::vector} 字面量传参，不引入 pgvector Java 客户端依赖；
 * 距离语义沿用 {@code <=>}（余弦距离，越小越相关），与 Chroma 适配器一致。</p>
 */
@Component
@Primary
@ConditionalOnProperty(name = "workbench.rag.vector-store", havingValue = "pgvector")
public class PgVectorStoreAdapter implements ScopedVectorStore {

    private static final Logger log = LoggerFactory.getLogger(PgVectorStoreAdapter.class);

    /** 与 V44 迁移的 vector(1024) 及 bge-m3 维度绑定；换嵌入模型必须同步改迁移与该值。 */
    static final int EMBEDDING_DIMENSIONS = 1024;
    private static final long REACHABILITY_CACHE_MILLIS = 10_000L;

    private final DocumentChunkRepository repository;
    private final ObjectProvider<EmbeddingModel> embeddingModelProvider;

    @PersistenceContext
    private EntityManager entityManager;

    private volatile boolean reachabilityCached;
    private volatile long reachabilityCheckedAtMillis;

    public PgVectorStoreAdapter(DocumentChunkRepository repository,
                                ObjectProvider<EmbeddingModel> embeddingModelProvider) {
        this.repository = repository;
        this.embeddingModelProvider = embeddingModelProvider;
    }

    @Override
    public String providerName() {
        return "pgvector";
    }

    @Override
    public boolean persistentStoreConfigured() {
        return embeddingModelProvider.getIfAvailable() != null;
    }

    /** pgvector 与业务同库：库连得上即向量可用，实测一次聚合查询并缓存。 */
    @Override
    public boolean persistentStoreReachable() {
        long now = System.currentTimeMillis();
        if (now - reachabilityCheckedAtMillis < REACHABILITY_CACHE_MILLIS) {
            return reachabilityCached;
        }
        boolean reachable;
        try {
            entityManager.createNativeQuery(
                    "select count(*) from document_chunks where embedding is not null")
                    .getSingleResult();
            reachable = true;
        } catch (RuntimeException exception) {
            log.warn("pgvector 连通性探测失败", exception);
            reachable = false;
        }
        reachabilityCached = reachable;
        reachabilityCheckedAtMillis = now;
        return reachable;
    }

    /**
     * 增量写入：切片行与向量在同一事务内落库，要么全成要么全不成。
     * 嵌入先于写库完成 —— 嵌入失败时不产生任何半成品数据。
     */
    @Override
    @Transactional
    public void addAll(List<SourceDocument> documents) {
        if (documents.isEmpty()) {
            return;
        }
        EmbeddingModel embeddingModel = requireEmbeddingModel();
        List<float[]> vectors = embeddingModel.embed(
                documents.stream().map(SourceDocument::content).toList());

        repository.deleteAllByIdInBatch(documents.stream().map(SourceDocument::id).toList());
        repository.saveAll(documents.stream().map(DocumentChunkEntity::new).toList());
        repository.flush();

        for (int index = 0; index < documents.size(); index++) {
            updateEmbedding(documents.get(index).id(), vectors.get(index));
        }
    }

    @Override
    @Transactional
    public void deleteByIds(List<String> ids) {
        if (!ids.isEmpty()) {
            repository.deleteAllByIdInBatch(ids);
        }
    }

    @Override
    @Transactional
    public void clear() {
        repository.deleteAllInBatch();
    }

    @Override
    @Transactional
    public void replaceAll(List<SourceDocument> documents) {
        clear();
        addAll(documents);
    }

    /**
     * 可见性变更只更新元数据列，不重嵌入（向量不变）—— 比 Chroma 的先删后加更轻。
     */
    @Override
    @Transactional
    public void refreshVisibilityMetadata(List<SourceDocument> documents) {
        for (SourceDocument document : documents) {
            entityManager.createNativeQuery(
                    "update document_chunks set visibility = :visibility, owner_user_id = :ownerUserId, "
                            + "workspace_id = :workspaceId where id = :id")
                    .setParameter("visibility", document.visibility().name())
                    .setParameter("ownerUserId", document.ownerUserId())
                    .setParameter("workspaceId", document.workspaceId())
                    .setParameter("id", document.id())
                    .executeUpdate();
        }
    }

    @Override
    public List<SourceDocument> similaritySearch(String query, int topK) {
        return scopedSearch(query, topK, null, null, false);
    }

    @Override
    public List<SourceDocument> similaritySearch(String query, int topK,
                                                 String ownerUserId, Set<String> readableWorkspaceIds) {
        return scopedSearch(query, topK, ownerUserId, readableWorkspaceIds, true);
    }

    @Override
    public List<SourceDocument> documentsByIds(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        List<?> rows = entityManager.createNativeQuery(
                "select " + SOURCE_COLUMNS + " from document_chunks "
                        + "where id in (:ids) and embedding is not null")
                .setParameter("ids", ids)
                .getResultList();
        return rows.stream().map(row -> toSourceDocument((Object[]) row, 0.0)).toList();
    }

    private List<SourceDocument> scopedSearch(String query, int topK, String ownerUserId,
                                              Set<String> readableWorkspaceIds, boolean scoped) {
        EmbeddingModel embeddingModel = requireEmbeddingModel();
        if (topK <= 0) {
            return List.of();
        }
        float[] queryVector = embeddingModel.embed(query);

        StringBuilder sql = new StringBuilder("select " + SOURCE_COLUMNS + ", "
                + "(embedding OPERATOR(public.<=>) cast(:queryVector as public.vector)) as distance "
                + "from document_chunks where embedding is not null and (");
        sql.append("visibility = 'PUBLIC'");
        if (ownerUserId != null && !ownerUserId.isBlank()) {
            sql.append(" or (visibility = 'PRIVATE' and owner_user_id = :ownerUserId");
            if (scoped && readableWorkspaceIds != null && !readableWorkspaceIds.isEmpty()) {
                sql.append(" and workspace_id in (:workspaceIds)");
            }
            sql.append(")");
        }
        if (scoped && readableWorkspaceIds != null && !readableWorkspaceIds.isEmpty()) {
            sql.append(" or (visibility = 'WORKSPACE' and workspace_id in (:workspaceIds))");
        }
        sql.append(") and category <> 'LEARNING_RECORD' order by distance limit :topK");

        var nativeQuery = entityManager.createNativeQuery(sql.toString())
                .setParameter("queryVector", vectorLiteral(queryVector))
                .setParameter("topK", topK);
        if (ownerUserId != null && !ownerUserId.isBlank()) {
            nativeQuery.setParameter("ownerUserId", ownerUserId);
        }
        if (scoped && readableWorkspaceIds != null && !readableWorkspaceIds.isEmpty()) {
            nativeQuery.setParameter("workspaceIds", new ArrayList<>(readableWorkspaceIds));
        }

        @SuppressWarnings("unchecked")
        List<Object[]> rows = (List<Object[]>) (Object) nativeQuery.getResultList();
        List<SourceDocument> results = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            double distance = row.length > SOURCE_COLUMN_COUNT && row[SOURCE_COLUMN_COUNT] != null
                    ? ((Number) row[SOURCE_COLUMN_COUNT]).doubleValue() : 0.0;
            results.add(toSourceDocument(row, distance));
        }
        return results;
    }

    private void updateEmbedding(String id, float[] vector) {
        entityManager.createNativeQuery(
                "update document_chunks set embedding = cast(:embedding as public.vector) where id = :id")
                .setParameter("embedding", vectorLiteral(vector))
                .setParameter("id", id)
                .executeUpdate();
    }

    /** 存量切片嵌入回填见 {@link VectorEmbeddingBackfillService}（无条件 bean，休眠期可用）。 */

    private EmbeddingModel requireEmbeddingModel() {
        EmbeddingModel embeddingModel = embeddingModelProvider.getIfAvailable();
        if (embeddingModel == null) {
            throw new IllegalStateException("EmbeddingModel 不可用，无法操作 pgvector 向量");
        }
        return embeddingModel;
    }

    private static String vectorLiteral(float[] vector) {
        StringBuilder builder = new StringBuilder(vector.length * 10 + 2);
        builder.append('[');
        for (int index = 0; index < vector.length; index++) {
            if (index > 0) {
                builder.append(',');
            }
            builder.append(vector[index]);
        }
        return builder.append(']').toString();
    }

    private static final String SOURCE_COLUMNS = "id, content, title, source, path, chunk_index, "
            + "document_id, file_name, content_hash, heading_path, heading_level, start_offset, "
            + "end_offset, chunk_type, category, owner_user_id, workspace_id, visibility, page_number";
    private static final int SOURCE_COLUMN_COUNT = 19;

    private static SourceDocument toSourceDocument(Object[] row, double score) {
        return new SourceDocument(
                stringAt(row, 0),
                stringAt(row, 1),
                stringAt(row, 2),
                stringAt(row, 3),
                stringAt(row, 4),
                intAt(row, 5),
                stringAt(row, 6),
                stringAt(row, 7),
                stringAt(row, 8),
                score,
                stringAt(row, 9),
                intAt(row, 10),
                intAt(row, 11),
                intAt(row, 12),
                stringAt(row, 13),
                stringAt(row, 14),
                stringAt(row, 15),
                stringAt(row, 16),
                DocumentVisibility.valueOf(stringAt(row, 17)),
                intAt(row, 18)
        );
    }

    private static String stringAt(Object[] row, int index) {
        Object value = row[index];
        return value == null ? null : value.toString();
    }

    private static int intAt(Object[] row, int index) {
        Object value = row[index];
        if (value instanceof Number number) {
            return number.intValue();
        }
        return value == null ? 0 : Integer.parseInt(value.toString());
    }
}
