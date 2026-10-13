package com.example.workbench.kg;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.Comment;

/**
 * 知识图谱实体节点。
 *
 * <p>两类：WIKI_PAGE（对应一张 wiki 页，id 固定为 {@code wiki:<pageId>}）与
 * TERM（用户通过 {@code [[概念名]]} 显式链接出来的概念，id 固定为 {@code term:<slug>}）。
 * 节点由确定性抽取维护，可追溯来源（source_kind / source_id）。
 */
@Entity
@Table(name = "kg_entities", uniqueConstraints = {
        @jakarta.persistence.UniqueConstraint(name = "uk_kg_entities_ws_type_name",
                columnNames = {"workspace_id", "entity_type", "name"})
})
@Comment("知识图谱实体（wiki 页 / 概念 term）")
public class KgEntity {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    @Comment("实体主键，确定性派生：wiki:<pageId> 或 term:<slug>")
    private String id;

    @Column(name = "workspace_id", nullable = false, length = 36)
    @Comment("所属知识空间主键")
    private String workspaceId;

    @Column(name = "entity_type", nullable = false, length = 32)
    @Comment("实体类型：WIKI_PAGE / TERM")
    private String entityType;

    @Column(name = "name", nullable = false, length = 512)
    @Comment("实体展示名（页面标题或概念名）")
    private String name;

    @Column(name = "source_kind", length = 32)
    @Comment("来源类型，同 entity_type 语义，便于追溯")
    private String sourceKind;

    @Column(name = "source_id", length = 512)
    @Comment("来源标识：WIKI_PAGE 为 pageId；TERM 为原始概念文本")
    private String sourceId;

    @Column(name = "mention_count", nullable = false)
    @Comment("提及计数（占位，图谱度数以关系实时计算为准）")
    private int mentionCount = 1;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Comment("创建时间")
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    @Comment("更新时间")
    private Instant updatedAt;

    protected KgEntity() {
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

    public String getEntityType() {
        return entityType;
    }

    public void setEntityType(String entityType) {
        this.entityType = entityType;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getSourceKind() {
        return sourceKind;
    }

    public void setSourceKind(String sourceKind) {
        this.sourceKind = sourceKind;
    }

    public String getSourceId() {
        return sourceId;
    }

    public void setSourceId(String sourceId) {
        this.sourceId = sourceId;
    }

    public int getMentionCount() {
        return mentionCount;
    }

    public void setMentionCount(int mentionCount) {
        this.mentionCount = mentionCount;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
