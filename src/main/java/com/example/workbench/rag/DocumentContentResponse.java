package com.example.workbench.rag;

public record DocumentContentResponse(
        String documentId,
        String fileName,
        String path,
        String category,
        String content,
        boolean sourceAvailable
) {
}
