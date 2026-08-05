package com.example.workbench.rag;

public record RebuildResult(
        String status,
        int clearedDocuments,
        int clearedChunks,
        int files,
        int failedFiles,
        int chunks,
        long durationMs
) {
}
