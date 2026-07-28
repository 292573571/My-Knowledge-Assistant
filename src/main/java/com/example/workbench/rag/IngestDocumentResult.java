package com.example.workbench.rag;

public record IngestDocumentResult(
        String fileName,
        String path,
        String documentId,
        String status,
        int chunks,
        String reason
) {
}
