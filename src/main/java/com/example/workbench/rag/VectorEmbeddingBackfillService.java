package com.example.workbench.rag;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 存量切片嵌入回填服务：为 document_chunks 中 embedding 为空的行补算向量。
 *
 * <p>无条件的独立 bean（不随 vector-store provider 条件装配）—— 两步发版时，
 * 第一步休眠期（仍走 Chroma）即可启动回填，第二步翻开关切换时向量已就绪，零空窗。</p>
 */
@Component
public class VectorEmbeddingBackfillService {

    private static final Logger log = LoggerFactory.getLogger(VectorEmbeddingBackfillService.class);

    private final ObjectProvider<EmbeddingModel> embeddingModelProvider;

    @PersistenceContext
    private EntityManager entityManager;

    public VectorEmbeddingBackfillService(ObjectProvider<EmbeddingModel> embeddingModelProvider) {
        this.embeddingModelProvider = embeddingModelProvider;
    }

    public long countMissingEmbeddings() {
        Number count = (Number) entityManager.createNativeQuery(
                "select count(*) from document_chunks where embedding is null").getSingleResult();
        return count.longValue();
    }

    /** 分批回填，返回本次处理条数；幂等，可反复调用直至 missing=0。 */
    @Transactional
    public int backfillMissingEmbeddings(int batchSize) {
        EmbeddingModel embeddingModel = requireEmbeddingModel();
        int processed = 0;
        while (processed < 10_000) {
            @SuppressWarnings("unchecked")
            List<Object[]> rows = (List<Object[]>) (Object) entityManager.createNativeQuery(
                    "select id, content from document_chunks "
                            + "where embedding is null order by id limit :batchSize")
                    .setParameter("batchSize", batchSize)
                    .getResultList();
            if (rows.isEmpty()) {
                break;
            }
            List<String> ids = new ArrayList<>(rows.size());
            List<String> contents = new ArrayList<>(rows.size());
            for (Object[] row : rows) {
                ids.add((String) row[0]);
                contents.add((String) row[1]);
            }
            List<float[]> vectors = embeddingModel.embed(contents);
            for (int index = 0; index < ids.size(); index++) {
                updateEmbedding(ids.get(index), vectors.get(index));
            }
            processed += ids.size();
            log.info("pgvector 回填进度 processed={} lastBatch={}", processed, ids.size());
        }
        return processed;
    }

    private void updateEmbedding(String id, float[] vector) {
        entityManager.createNativeQuery(
                "update document_chunks set embedding = cast(:embedding as vector) where id = :id")
                .setParameter("embedding", vectorLiteral(vector))
                .setParameter("id", id)
                .executeUpdate();
    }

    private EmbeddingModel requireEmbeddingModel() {
        EmbeddingModel embeddingModel = embeddingModelProvider.getIfAvailable();
        if (embeddingModel == null) {
            throw new IllegalStateException("EmbeddingModel 不可用，无法回填向量");
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
}
