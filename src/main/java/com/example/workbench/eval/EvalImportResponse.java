package com.example.workbench.eval;

import java.time.Instant;

public record EvalImportResponse(Long id, String originalFileName, String contentType, long fileSize, int importedCount, Instant createdAt) {
}
