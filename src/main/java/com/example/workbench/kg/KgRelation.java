package com.example.workbench.kg;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.Comment;

/**
 * 知识图谱关系边。
 *
 * <p>三类：PARENT_CHILD（wiki 页层级，child→parent）、WIKI_LINK（页间 {@code [[链接]]} 解析到另一页）、
 * MENTIONS（页→概念 term 的提及）。evidence_source_id 记产生该边的页面 id，用于幂等重建时按页清理。
 */
@Entity
@Table(name = "kg_relations", uniqueConstraints = {
        @jakarta.persistence.UniqueConstraint(name = "uk_kg_relations_uniq",
                columnNames = {"workspace_id", "source_entity_id", "target_entity_id", "relation_type"})
})
@Comment("知识图谱关系")
public class KgRelation {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    @Comment("关系主键")
    private String id;

    @Column(name = "workspace_id", nullable = false, length = 36)
    @Comment("所属知识空间主键")
    private String workspaceId;

    @Column(name = "source_entity_id", nullable = false, length = 36)
    @Comment("源实体 id")
    private String sourceEntityId;

    @Column(name = "target_entity_id", nullable = false, length = 36)
    @Comment("目标实体 id")
    private String targetEntityId;

    @Column(name = "relation_type", nullable = false, length = 32)
    @Comment("关系类型：PARENT_CHILD / WIKI_LINK / MENTIONS")
    private String relationType;

    @Column(name = "weight", nullable = false)
    @Comment("关系权重，默认 1")
    private int weight = 1;

    @Column(name = "evidence_source_id", length = 512)
    @Comment("产生该边的页面 id（幂等重建时按页清理）")
    private String evidenceSourceId;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Comment("创建时间")
    private Instant createdAt;

    protected KgRelation() {
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getWorkspaceId() {
        return workspaceId;
    }

    public void setWorkspaceId(String workspaceId) {
        this.workspaceId = workspaceId;
    }

    public String getSourceEntityId() {
        return sourceEntityId;
    }

    public void setSourceEntityId(String sourceEntityId) {
        this.sourceEntityId = sourceEntityId;
    }

    public String getTargetEntityId() {
        return targetEntityId;
    }

    public void setTargetEntityId(String targetEntityId) {
        this.targetEntityId = targetEntityId;
    }

    public String getRelationType() {
        return relationType;
    }

    public void setRelationType(String relationType) {
        this.relationType = relationType;
    }

    public int getWeight() {
        return weight;
    }

    public void setWeight(int weight) {
        this.weight = weight;
    }

    public String getEvidenceSourceId() {
        return evidenceSourceId;
    }

    public void setEvidenceSourceId(String evidenceSourceId) {
        this.evidenceSourceId = evidenceSourceId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
