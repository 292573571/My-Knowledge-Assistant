package com.example.workbench.agent;

import com.example.workbench.rag.DocumentIndexEntry;

/**
 * 面向 Agent 的精简文档信息。
 */
public record IndexedDocumentSummary(
        String documentId,
        String fileName,
        String path,
        int chunkCount,
        String status,
        String visibility
) {
    static IndexedDocumentSummary from(DocumentIndexEntry document) {
        return new IndexedDocumentSummary(document.documentId(), document.fileName(), document.path(),
                document.chunkCount(), document.indexStatus(), document.visibility().name());
    }
}
