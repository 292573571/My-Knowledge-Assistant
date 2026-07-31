package com.example.workbench.rag;

import java.util.List;

public record ParsedDocument(
        String documentType,
        String content,
        String title,
        List<DocumentBlock> blocks
) {

    public ParsedDocument {
        blocks = List.copyOf(blocks);
    }
}
