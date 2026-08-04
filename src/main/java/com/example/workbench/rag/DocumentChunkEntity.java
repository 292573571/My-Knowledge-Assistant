package com.example.workbench.rag;

import com.example.workbench.workspace.DocumentVisibility;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.Comment;

@Entity
@Table(name = "document_chunks")
@Comment("知识文档分块稀疏检索表")
/**
 * 保存可供 PostgreSQL 稀疏检索的文档分块和结构化元数据。
 */
public class DocumentChunkEntity {

    @Id
    @Column(length = 196)
    @Comment("文档分块唯一标识")
    private String id;
    @Column(name = "document_id", nullable = false, length = 128)
    @Comment("所属业务文档标识")
    private String documentId;
    @Column(name = "chunk_index", nullable = false)
    @Comment("分块在文档中的顺序")
    private int chunkIndex;
    @Column(nullable = false, columnDefinition = "text")
    @Comment("分块正文")
    private String content;
    @Column(columnDefinition = "text")
    @Comment("分块或文档标题")
    private String title;
    @Column(nullable = false)
    @Comment("来源显示名称")
    private String source;
    @Column(nullable = false)
    @Comment("文档源文件路径")
    private String path;
    @Column(name = "file_name", nullable = false)
    @Comment("原始文件名")
    private String fileName;
    @Column(name = "file_type", nullable = false, length = 16)
    @Comment("文件扩展类型")
    private String fileType;
    @Column(name = "content_hash", nullable = false, length = 128)
    @Comment("文档内容哈希")
    private String contentHash;
    @Column(name = "heading_path", columnDefinition = "text")
    @Comment("分块标题层级路径")
    private String headingPath;
    @Column(name = "heading_level", nullable = false)
    @Comment("分块标题层级")
    private int headingLevel;
    @Column(name = "start_offset", nullable = false)
    @Comment("分块在原文中的开始偏移")
    private int startOffset;
    @Column(name = "end_offset", nullable = false)
    @Comment("分块在原文中的结束偏移")
    private int endOffset;
    @Column(name = "chunk_type", nullable = false, length = 32)
    @Comment("分块结构类型")
    private String chunkType;
    @Column(nullable = false, length = 32)
    @Comment("文档业务分类")
    private String category;
    @Column(name = "owner_user_id", nullable = false, length = 64)
    @Comment("文档所有者业务标识")
    private String ownerUserId;
    @Column(name = "workspace_id", nullable = false, length = 36)
    @Comment("所属知识空间主键")
    private String workspaceId;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    @Comment("文档可见性")
    private DocumentVisibility visibility;
    @Column(name = "page_number", nullable = false)
    @Comment("来源页码，非分页文档为零")
    private int pageNumber;
    @Column(name = "created_at", nullable = false, updatable = false)
    @Comment("分块索引创建时间")
    private Instant createdAt;
    @Column(name = "document_version", nullable = false)
    @Comment("文档索引版本")
    private long documentVersion;

    protected DocumentChunkEntity() {
    }

    DocumentChunkEntity(SourceDocument sourceDocument) {
        this.id = sourceDocument.id();
        this.documentId = sourceDocument.documentId();
        this.chunkIndex = sourceDocument.chunkIndex();
        this.content = sourceDocument.content();
        this.title = sourceDocument.title();
        this.source = sourceDocument.source();
        this.path = sourceDocument.path();
        this.fileName = sourceDocument.fileName();
        this.fileType = fileType(sourceDocument.fileName());
        this.contentHash = sourceDocument.contentHash();
        this.headingPath = sourceDocument.headingPath();
        this.headingLevel = sourceDocument.headingLevel();
        this.startOffset = sourceDocument.startOffset();
        this.endOffset = sourceDocument.endOffset();
        this.chunkType = sourceDocument.chunkType();
        this.category = sourceDocument.category();
        this.ownerUserId = sourceDocument.ownerUserId();
        this.workspaceId = sourceDocument.workspaceId();
        this.visibility = sourceDocument.visibility();
        this.pageNumber = sourceDocument.pageNumber();
        this.createdAt = Instant.now();
        this.documentVersion = 1;
    }

    SourceDocument toSourceDocument(double score) {
        return new SourceDocument(id, content, title, source, path, chunkIndex, documentId, fileName,
                contentHash, score, headingPath, headingLevel, startOffset, endOffset, chunkType, category,
                ownerUserId, workspaceId, visibility, pageNumber);
    }

    String searchableText() {
        return String.join("\n", value(fileName), value(title), value(headingPath), value(content));
    }

    String documentId() {
        return documentId;
    }

    int chunkIndex() {
        return chunkIndex;
    }

    String headingPath() {
        return headingPath;
    }

    int pageNumber() {
        return pageNumber;
    }

    private static String fileType(String fileName) {
        int dot = fileName == null ? -1 : fileName.lastIndexOf('.');
        return dot < 0 ? "unknown" : fileName.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
    }

    private static String value(String value) {
        return value == null ? "" : value;
    }
}
