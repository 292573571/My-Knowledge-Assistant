package com.example.workbench.rag;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import com.example.workbench.workspace.DocumentVisibility;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import org.hibernate.annotations.Comment;
import java.time.Instant;

@Entity
@Table(name = "document_indexes", uniqueConstraints = {
        @jakarta.persistence.UniqueConstraint(name = "uk_document_indexes_workspace_document", columnNames = {"workspace_id", "document_id"}),
        @jakarta.persistence.UniqueConstraint(name = "uk_document_indexes_workspace_path", columnNames = {"workspace_id", "path"}),
        @jakarta.persistence.UniqueConstraint(name = "uk_document_indexes_workspace_hash", columnNames = {"workspace_id", "content_hash"})
})
@Comment("知识文档索引表")
public class DocumentIndexEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Comment("索引记录主键")
    private Long id;

    @Column(name = "document_id", nullable = false, length = 128)
    @Comment("业务文档标识")
    private String documentId;
    @Column(name = "file_name", nullable = false)
    @Comment("原始文件名")
    private String fileName;
    @Column(nullable = false)
    @Comment("文档源文件路径")
    private String path;
    @Column(name = "content_hash", nullable = false, length = 128)
    @Comment("文档内容哈希")
    private String contentHash;
    @Column(name = "chunk_count", nullable = false)
    @Comment("文档分块数量")
    private int chunkCount;
    @Column(name = "ingested_at", nullable = false)
    @Comment("导入时间戳")
    private Instant ingestedAt;
    @Column(nullable = false, length = 32)
    @Comment("文档分类")
    private String category;
    @Column(name = "index_status", nullable = false, length = 32)
    @Comment("索引状态")
    private String indexStatus;
    @Column(name = "owner_user_id", nullable = false, length = 64)
    @Comment("文档所有者业务标识")
    private String ownerUserId;
    @Column(name = "workspace_id", nullable = false, length = 36)
    @Comment("所属知识空间主键")
    private String workspaceId;
    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    @Comment("文档可见性：私有、空间或公共")
    private DocumentVisibility visibility;

    protected DocumentIndexEntity() {
    }

    DocumentIndexEntity(DocumentIndexEntry entry) {
        this.documentId = entry.documentId();
        this.fileName = entry.fileName();
        this.path = entry.path();
        this.contentHash = entry.contentHash();
        this.chunkCount = entry.chunkCount();
        this.ingestedAt = entry.ingestedAt();
        this.category = entry.category();
        this.indexStatus = entry.indexStatus();
        this.ownerUserId = entry.ownerUserId();
        this.workspaceId = entry.workspaceId();
        this.visibility = entry.visibility();
    }

    DocumentIndexEntry toEntry() {
        return new DocumentIndexEntry(documentId, fileName, path, contentHash, chunkCount, ingestedAt,
                category, indexStatus, ownerUserId, workspaceId, visibility);
    }
}
