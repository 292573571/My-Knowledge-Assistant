package com.example.workbench.rag;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 从真实索引中抽取分块样本，供评测题草稿生成使用。
 *
 * <p>按文档轮流取样：先保证每份文档都有代表分块，再补第二、第三块，
 * 避免题型全部集中在某一篇长文档上。</p>
 */
@Component
public class KnowledgeChunkSampler {

    private static final int CHUNKS_PER_DOCUMENT = 8;

    @PersistenceContext
    private EntityManager entityManager;

    public record ChunkSample(String documentId, String fileName, String headingPath, String content) {
    }

    @Transactional(readOnly = true)
    public List<ChunkSample> sample(int limit) {
        if (limit <= 0) {
            return List.of();
        }
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery("""
                select document_id, file_name, heading_path, content, rn from (
                  select c.document_id, c.file_name, c.heading_path, c.content,
                         row_number() over (partition by c.document_id order by c.chunk_index) as rn
                  from document_chunks c
                  where c.category <> 'LEARNING_RECORD'
                    and c.content is not null
                    and length(c.content) between 80 and 1500
                ) ranked
                where rn <= :perDocument
                order by rn, document_id
                """)
                .setParameter("perDocument", CHUNKS_PER_DOCUMENT)
                .setMaxResults(Math.max(limit * 3, 40))
                .getResultList();

        Map<String, List<ChunkSample>> byDocument = new LinkedHashMap<>();
        for (Object[] row : rows) {
            ChunkSample sample = new ChunkSample(
                    String.valueOf(row[0]),
                    row[1] == null ? "" : String.valueOf(row[1]),
                    row[2] == null ? "" : String.valueOf(row[2]),
                    String.valueOf(row[3]));
            byDocument.computeIfAbsent(sample.documentId(), key -> new ArrayList<>()).add(sample);
        }

        List<ChunkSample> ordered = new ArrayList<>();
        for (int round = 0; round < CHUNKS_PER_DOCUMENT; round++) {
            for (List<ChunkSample> samples : byDocument.values()) {
                if (round < samples.size()) {
                    ordered.add(samples.get(round));
                }
            }
        }
        return ordered.size() > limit ? ordered.subList(0, limit) : ordered;
    }
}
