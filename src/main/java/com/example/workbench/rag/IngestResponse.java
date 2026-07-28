package com.example.workbench.rag;

import java.util.List;

public record IngestResponse(
        int files,
        int chunks,
        int imported,
        int skipped,
        int failed,
        List<IngestDocumentResult> documents
) {
    public IngestResponse(int files, int chunks) {
        this(files, chunks, files, 0, 0, List.of());
    }

    public IngestResponse(IngestResult result) {
        this(result.files(), result.documents(), result.files(), 0, 0, List.of());
    }
}
