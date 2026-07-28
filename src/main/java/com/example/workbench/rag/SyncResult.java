package com.example.workbench.rag;

public record SyncResult(
        String status,
        int scannedFiles,
        int addedFiles,
        int updatedFiles,
        int unchangedFiles,
        int deletedFiles,
        int addedChunks,
        int deletedChunks,
        long durationMs
) {
}
