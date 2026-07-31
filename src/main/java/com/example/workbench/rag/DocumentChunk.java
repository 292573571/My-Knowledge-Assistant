package com.example.workbench.rag;

public record DocumentChunk(
        String content,
        int chunkIndex,
        String headingPath,
        int headingLevel,
        int startOffset,
        int endOffset,
        String chunkType,
        int pageNumber
) {

    public DocumentChunk(
            String content, int chunkIndex, String headingPath, int headingLevel,
            int startOffset, int endOffset, String chunkType
    ) {
        this(content, chunkIndex, headingPath, headingLevel, startOffset, endOffset, chunkType, 0);
    }
}
