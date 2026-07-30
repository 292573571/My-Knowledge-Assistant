package com.example.workbench.eval;

import com.example.workbench.auth.AppUser;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.Comment;

@Entity
@Table(name = "eval_imports")
@Comment("评测用例导入记录表")
public class EvalImportEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) @Comment("导入记录主键") private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "owner_user_id", nullable = false) @Comment("所属用户主键") private AppUser owner;
    @Column(name = "original_file_name", nullable = false, length = 255) @Comment("原始文件名") private String originalFileName;
    @Column(name = "stored_file_name", nullable = false, length = 255) @Comment("存储文件名") private String storedFileName;
    @Column(name = "content_type", nullable = false, length = 128) @Comment("文件媒体类型") private String contentType;
    @Column(name = "file_size", nullable = false) @Comment("文件大小（字节）") private long fileSize;
    @Column(name = "imported_count", nullable = false) @Comment("成功导入用例数量") private int importedCount;
    @Column(name = "created_at", nullable = false) @Comment("导入时间") private Instant createdAt;

    protected EvalImportEntity() { }

    EvalImportEntity(AppUser owner, String originalFileName, String storedFileName, String contentType, long fileSize, int importedCount) {
        this.owner = owner; this.originalFileName = originalFileName; this.storedFileName = storedFileName;
        this.contentType = contentType; this.fileSize = fileSize; this.importedCount = importedCount; this.createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getOriginalFileName() { return originalFileName; }
    public String getStoredFileName() { return storedFileName; }
    public String getContentType() { return contentType; }
    public long getFileSize() { return fileSize; }
    public int getImportedCount() { return importedCount; }
    public Instant getCreatedAt() { return createdAt; }
}
