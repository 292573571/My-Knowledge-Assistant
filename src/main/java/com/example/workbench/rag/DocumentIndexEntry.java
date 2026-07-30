package com.example.workbench.rag;

import com.example.workbench.workspace.DocumentVisibility;

public record DocumentIndexEntry(
        String documentId,
        String fileName,
        String path,
        String contentHash,
        int chunkCount,
        long ingestedAt,
        String category,
        String indexStatus,
        String ownerUserId,
        String workspaceId,
        DocumentVisibility visibility
) {
    public DocumentIndexEntry(String documentId, String fileName, String path, String contentHash, int chunkCount, long ingestedAt) {
        this(documentId, fileName, path, contentHash, chunkCount, ingestedAt, "SOURCE", "INDEXED", "", "public-default", DocumentVisibility.PUBLIC);
    }

    public DocumentIndexEntry(String documentId, String fileName, String path, String contentHash, int chunkCount,
                              long ingestedAt, String category, String indexStatus, String ownerUserId) {
        this(documentId, fileName, path, contentHash, chunkCount, ingestedAt, category, indexStatus, ownerUserId,
                defaultWorkspace(ownerUserId), defaultVisibility(ownerUserId));
    }

    public DocumentIndexEntry {
        category = category == null || category.isBlank() ? "SOURCE" : category;
        indexStatus = indexStatus == null || indexStatus.isBlank() ? "INDEXED" : indexStatus;
        ownerUserId = ownerUserId == null ? "" : ownerUserId;
        workspaceId = workspaceId == null || workspaceId.isBlank() ? defaultWorkspace(ownerUserId) : workspaceId;
        visibility = visibility == null ? defaultVisibility(ownerUserId) : visibility;
    }

    private static String defaultWorkspace(String ownerUserId) {
        return ownerUserId == null || ownerUserId.isBlank() ? "public-default" : "personal-" + ownerUserId;
    }

    private static DocumentVisibility defaultVisibility(String ownerUserId) {
        return ownerUserId == null || ownerUserId.isBlank() ? DocumentVisibility.PUBLIC : DocumentVisibility.PRIVATE;
    }
}
