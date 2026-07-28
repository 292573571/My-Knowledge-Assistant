package com.example.workbench.rag;

public record IngestDocumentRequest(
        String path,
        boolean force
) {
}
