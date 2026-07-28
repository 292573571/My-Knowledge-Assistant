package com.example.workbench.rag;

import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class DocumentChunkerRouter {

    private final List<DocumentChunker> chunkers;

    public DocumentChunkerRouter(List<DocumentChunker> chunkers) {
        this.chunkers = chunkers;
    }

    public DocumentChunker select(String fileName) {
        return chunkers.stream()
                .filter(chunker -> chunker.supports(fileName))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unsupported document type: " + fileName));
    }
}
