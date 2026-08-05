package com.example.workbench.rag;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import org.hibernate.annotations.Comment;

/**
 * 保存文档批次切块产物，供失败任务恢复和最终索引发布使用。
 */
@Entity
@Table(name = "document_task_batch_artifacts")
@Comment("长文档批次切块产物表")
public class DocumentTaskBatchArtifactEntity {

    @Id
    @Column(name = "artifact_id", length = 80)
    @Comment("批次产物业务标识")
    private String artifactId;

    @Column(name = "task_id", nullable = false, length = 36)
    @Comment("所属任务标识")
    private String taskId;

    @Column(name = "batch_index", nullable = false)
    @Comment("批次序号")
    private int batchIndex;

    @Lob
    @Column(name = "payload", nullable = false, columnDefinition = "text")
    @Comment("批次文档片段JSON")
    private String payload;

    protected DocumentTaskBatchArtifactEntity() {
    }

    public DocumentTaskBatchArtifactEntity(String taskId, int batchIndex, String payload) {
        this.artifactId = taskId + "-batch-" + batchIndex;
        this.taskId = taskId;
        this.batchIndex = batchIndex;
        this.payload = payload;
    }

    public String getArtifactId() { return artifactId; }
    public String getTaskId() { return taskId; }
    public int getBatchIndex() { return batchIndex; }
    public String getPayload() { return payload; }
}
