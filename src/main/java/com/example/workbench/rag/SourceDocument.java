package com.example.workbench.rag;

import com.example.workbench.workspace.DocumentVisibility;

public record SourceDocument(
        String id,
        String content,
        String title,
        String source,
        String path,
        int chunkIndex,
        String documentId,
        String fileName,
        String contentHash,
        double score,
        String headingPath,
        int headingLevel,
        int startOffset,
        int endOffset,
        String chunkType,
        String category,
        String ownerUserId,
        String workspaceId,
        DocumentVisibility visibility,
        int pageNumber
) {
    public SourceDocument(
            String id, String content, String title, String source, String path, int chunkIndex,
            String documentId, String fileName, String contentHash, double score, String headingPath,
            int headingLevel, int startOffset, int endOffset, String chunkType, String category,
            String ownerUserId, String workspaceId, DocumentVisibility visibility
    ) {
        this(id, content, title, source, path, chunkIndex, documentId, fileName, contentHash, score,
                headingPath, headingLevel, startOffset, endOffset, chunkType, category, ownerUserId,
                workspaceId, visibility, 0);
    }

    public SourceDocument(String id, String content, String title, String source, String path, int chunkIndex) {
        this(id, content, title, source, path, chunkIndex, source, source, "", 0.0, title, 0, 0,
                content.length(), "text-paragraph", "SOURCE", "", "public-default", DocumentVisibility.PUBLIC, 0);
    }

    public SourceDocument(String id, String content, String title, String source, String path, int chunkIndex,
                          String documentId, String fileName, String contentHash, double score, String headingPath,
                          int headingLevel, int startOffset, int endOffset, String chunkType, String category,
                          String ownerUserId, String workspaceId, DocumentVisibility visibility, long pageNumber) {
        this(id, content, title, source, path, chunkIndex, documentId, fileName, contentHash, score, headingPath,
                headingLevel, startOffset, endOffset, chunkType, category, ownerUserId, workspaceId, visibility,
                Math.toIntExact(pageNumber));
    }

    public SourceDocument(
            String id, String content, String title, String source, String path, int chunkIndex,
            String documentId, String fileName, String contentHash, double score, String headingPath,
            int headingLevel, int startOffset, int endOffset, String chunkType
    ) {
        this(id, content, title, source, path, chunkIndex, documentId, fileName, contentHash, score,
                headingPath, headingLevel, startOffset, endOffset, chunkType, "SOURCE", "", "public-default", DocumentVisibility.PUBLIC);
    }

    public SourceDocument(
            String id, String title, String source, String path, String documentId, String fileName,
            String contentHash, DocumentChunk chunk, String category, String ownerUserId,
            String workspaceId, DocumentVisibility visibility
    ) {
        this(id, chunk.content(), title, source, path, chunk.chunkIndex(), documentId, fileName, contentHash, 0.0,
                chunk.headingPath(), chunk.headingLevel(), chunk.startOffset(), chunk.endOffset(), chunk.chunkType(),
                category, ownerUserId, workspaceId, visibility, chunk.pageNumber());
    }

    public SourceDocument(String id, String content, String title, String source, String path, int chunkIndex,
                          String documentId, String fileName, String contentHash, double score, String headingPath,
                          int headingLevel, int startOffset, int endOffset, String chunkType, String category,
                          String ownerUserId) {
        this(id, content, title, source, path, chunkIndex, documentId, fileName, contentHash, score, headingPath,
                headingLevel, startOffset, endOffset, chunkType, category, ownerUserId,
                defaultWorkspace(ownerUserId), defaultVisibility(ownerUserId));
    }

    public SourceDocument(
            String id,
            String title,
            String source,
            String path,
            String documentId,
            String fileName,
            String contentHash,
            DocumentChunk chunk,
            String category,
            String ownerUserId
    ) {
        this(
                id,
                chunk.content(),
                title,
                source,
                path,
                chunk.chunkIndex(),
                documentId,
                fileName,
                contentHash,
                0.0,
                chunk.headingPath(),
                chunk.headingLevel(),
                chunk.startOffset(),
                chunk.endOffset(),
                chunk.chunkType(),
                category,
                ownerUserId,
                defaultWorkspace(ownerUserId),
                defaultVisibility(ownerUserId),
                chunk.pageNumber()
        );
    }

    public SourceDocument withScore(double score) {
        return new SourceDocument(
                id,
                content,
                title,
                source,
                path,
                chunkIndex,
                documentId,
                fileName,
                contentHash,
                score,
                headingPath,
                headingLevel,
                startOffset,
                endOffset,
                chunkType,
                category,
                ownerUserId,
                workspaceId,
                visibility,
                pageNumber
        );
    }

    public SourceDocument {
        category = category == null || category.isBlank() ? "SOURCE" : category;
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
