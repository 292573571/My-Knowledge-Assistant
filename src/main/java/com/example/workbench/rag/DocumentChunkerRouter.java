package com.example.workbench.rag;

import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class DocumentChunkerRouter {

    private final List<DocumentChunker> chunkers;

    public DocumentChunkerRouter(List<DocumentChunker> chunkers) {
        this.chunkers = chunkers;
    }

    public DocumentChunker select(ParsedDocument document) {
        return chunkers.stream()
                .filter(chunker -> chunker.supports(document))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unsupported parsed document type: " + document.documentType()
                ));
    }
}
