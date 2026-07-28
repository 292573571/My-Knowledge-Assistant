package com.example.workbench.rag;

public record DocumentIndexEntry(
        String documentId,
        String fileName,
        String path,
        String contentHash,
        int chunkCount,
        long ingestedAt,
        String category,
        String indexStatus,
        String ownerUserId
) {
    public DocumentIndexEntry(String documentId, String fileName, String path, String contentHash, int chunkCount, long ingestedAt) {
        this(documentId, fileName, path, contentHash, chunkCount, ingestedAt, "SOURCE", "INDEXED", "");
    }

    public DocumentIndexEntry {
        category = category == null || category.isBlank() ? "SOURCE" : category;
        indexStatus = indexStatus == null || indexStatus.isBlank() ? "INDEXED" : indexStatus;
        ownerUserId = ownerUserId == null ? "" : ownerUserId;
    }
}
