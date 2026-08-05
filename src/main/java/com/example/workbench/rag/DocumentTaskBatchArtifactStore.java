package com.example.workbench.rag;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * 读写长文档批次切块产物。
 */
@Component
public class DocumentTaskBatchArtifactStore {

    private final DocumentTaskBatchArtifactRepository repository;
    private final ObjectMapper objectMapper;

    public DocumentTaskBatchArtifactStore(DocumentTaskBatchArtifactRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    /**
     * 保存或覆盖一个批次的切块产物。
     *
     * @param taskId 任务标识
     * @param batchIndex 批次序号
     * @param chunks 文档切块
     */
    public void save(String taskId, int batchIndex, String title, List<DocumentChunk> chunks) {
        try {
            repository.saveAndFlush(new DocumentTaskBatchArtifactEntity(
                    taskId, batchIndex, objectMapper.writeValueAsString(new SavedBatch(title, chunks))));
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new IllegalStateException("无法保存文档批次产物", exception);
        }
    }

    /**
     * 按批次序号读取任务的全部切块产物。
     *
     * @param taskId 任务标识
     * @return 批次序号到切块列表的映射
     */
    public Map<Integer, SavedBatch> load(String taskId) {
        return repository.findByTaskIdOrderByBatchIndex(taskId).stream().collect(Collectors.toMap(
                DocumentTaskBatchArtifactEntity::getBatchIndex,
                artifact -> readBatch(artifact.getPayload()),
                (left, right) -> right,
                java.util.LinkedHashMap::new));
    }

    /**
     * 删除失败批次的暂存产物，使下次执行重新解析该批次。
     *
     * @param taskId 任务标识
     * @param batchIndex 批次序号
     */
    public void delete(String taskId, int batchIndex) {
        repository.deleteById(taskId + "-batch-" + batchIndex);
    }

    /**
     * 删除一个任务的全部临时批次产物。
     *
     * @param taskId 任务标识
     */
    public void deleteAll(String taskId) {
        repository.deleteByTaskId(taskId);
    }

    private SavedBatch readBatch(String payload) {
        try {
            return objectMapper.readValue(payload, new TypeReference<>() { });
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new IllegalStateException("文档批次产物已损坏", exception);
        }
    }

    /**
     * 一个已持久化批次的标题和切块。
     *
     * @param title 文档标题
     * @param chunks 批次切块
     */
    public record SavedBatch(String title, List<DocumentChunk> chunks) {
        public SavedBatch {
            chunks = List.copyOf(chunks);
        }
    }
}
