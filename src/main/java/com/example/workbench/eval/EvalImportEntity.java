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

@Entity
@Table(name = "eval_imports")
public class EvalImportEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "owner_user_id", nullable = false) private AppUser owner;
    @Column(name = "original_file_name", nullable = false, length = 255) private String originalFileName;
    @Column(name = "stored_file_name", nullable = false, length = 255) private String storedFileName;
    @Column(name = "content_type", nullable = false, length = 128) private String contentType;
    @Column(name = "file_size", nullable = false) private long fileSize;
    @Column(name = "imported_count", nullable = false) private int importedCount;
    @Column(name = "created_at", nullable = false) private Instant createdAt;

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
