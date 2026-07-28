package com.example.workbench.rag;

public record IngestDirectoryRequest(
        String path,
        boolean force
) {
}
