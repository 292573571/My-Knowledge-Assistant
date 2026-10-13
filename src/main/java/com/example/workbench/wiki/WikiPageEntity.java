package com.example.workbench.wiki;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.Comment;

/**
 * wiki 模式知识库的页面实体。
 *
 * <p>页面以「可编辑的 Markdown 文档」为最小知识单位，通过 parent_id 形成层级（目录树），
 * workspace_id + slug 唯一标识一个页面。内容为 Markdown 源文本，保存时由 WikiPageService
 * 投影到 docs/wiki/&lt;workspaceId&gt;/&lt;pageId&gt;.md 并交由 DocumentIngestionService 切片向量化，
 * 使页面正文可被 RAG 检索召回（类目 WIKI_PAGE）。
 */
@Entity
@Table(name = "wiki_pages", uniqueConstraints = {
        @jakarta.persistence.UniqueConstraint(name = "uk_wiki_pages_workspace_slug", columnNames = {"workspace_id", "slug"})
})
@Comment("wiki 模式知识库页面表")
public class WikiPageEntity {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    @Comment("页面主键")
    private String id;

    @Column(name = "workspace_id", nullable = false, length = 36)
    @Comment("所属知识空间主键")
    private String workspaceId;

    @Column(name = "parent_id", length = 36)
    @Comment("父页面主键，构成层级目录树")
    private String parentId;

    @Column(name = "title", nullable = false, length = 255)
    @Comment("页面标题")
    private String title;

    @Column(name = "slug", nullable = false, length = 255)
    @Comment("页面短链标识，空间内唯一")
    private String slug;

    @Column(name = "content_md", columnDefinition = "text")
    @Comment("页面 Markdown 正文")
    private String contentMd;

    @Column(name = "owner_id", nullable = false, length = 64)
    @Comment("页面所有者用户主键")
    private String ownerId;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Comment("创建时间")
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    @Comment("更新时间")
    private Instant updatedAt;

    protected WikiPageEntity() {
    }

    public WikiPageEntity(String id, String workspaceId, String parentId, String title, String slug,
                          String contentMd, String ownerId, Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.workspaceId = workspaceId;
        this.parentId = parentId;
        this.title = title;
        this.slug = slug;
        this.contentMd = contentMd;
        this.ownerId = ownerId;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public String getId() {
        return id;
    }

    public String getWorkspaceId() {
        return workspaceId;
    }

    public String getParentId() {
        return parentId;
    }

    public void setParentId(String parentId) {
        this.parentId = parentId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getSlug() {
        return slug;
    }

    public void setSlug(String slug) {
        this.slug = slug;
    }

    public String getContentMd() {
        return contentMd;
    }

    public void setContentMd(String contentMd) {
        this.contentMd = contentMd;
    }

    public String getOwnerId() {
        return ownerId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
