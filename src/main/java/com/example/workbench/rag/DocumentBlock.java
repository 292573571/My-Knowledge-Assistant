package com.example.workbench.rag;

public record DocumentBlock(
        String content,
        String blockType,
        String headingPath,
        int headingLevel,
        int startOffset,
        int endOffset,
        int pageNumber
) {

    public DocumentBlock(
            String content, String blockType, String headingPath, int headingLevel, int startOffset, int endOffset
    ) {
        this(content, blockType, headingPath, headingLevel, startOffset, endOffset, 0);
    }
}
