package com.example.workbench.rag;

public record DocumentChunk(
        String content,
        int chunkIndex,
        String headingPath,
        int headingLevel,
        int startOffset,
        int endOffset,
        String chunkType
) {
}
