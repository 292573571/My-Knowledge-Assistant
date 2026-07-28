package com.example.workbench.rag;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "document_indexes")
public class DocumentIndexEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "document_id", nullable = false, unique = true, length = 128)
    private String documentId;
    @Column(name = "file_name", nullable = false)
    private String fileName;
    @Column(nullable = false, unique = true)
    private String path;
    @Column(name = "content_hash", nullable = false, unique = true, length = 128)
    private String contentHash;
    @Column(name = "chunk_count", nullable = false)
    private int chunkCount;
    @Column(name = "ingested_at", nullable = false)
    private long ingestedAt;
    @Column(nullable = false, length = 32)
    private String category;
    @Column(name = "index_status", nullable = false, length = 32)
    private String indexStatus;
    @Column(name = "owner_user_id", nullable = false, length = 64)
    private String ownerUserId;

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
    }

    DocumentIndexEntry toEntry() {
        return new DocumentIndexEntry(documentId, fileName, path, contentHash, chunkCount, ingestedAt, category, indexStatus, ownerUserId);
    }
}
