package com.example.workbench.rag;

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
        String ownerUserId
) {
    public SourceDocument(String id, String content, String title, String source, String path, int chunkIndex) {
        this(id, content, title, source, path, chunkIndex, source, source, "", 0.0, title, 0, 0, content.length(), "text-paragraph", "SOURCE", "");
    }

    public SourceDocument(
            String id, String content, String title, String source, String path, int chunkIndex,
            String documentId, String fileName, String contentHash, double score, String headingPath,
            int headingLevel, int startOffset, int endOffset, String chunkType
    ) {
        this(id, content, title, source, path, chunkIndex, documentId, fileName, contentHash, score,
                headingPath, headingLevel, startOffset, endOffset, chunkType, "SOURCE", "");
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
                ownerUserId
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
                ownerUserId
        );
    }

    public SourceDocument {
        category = category == null || category.isBlank() ? "SOURCE" : category;
        ownerUserId = ownerUserId == null ? "" : ownerUserId;
    }
}
