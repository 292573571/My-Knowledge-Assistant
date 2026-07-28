package com.example.workbench.rag;

public record WorkbenchStatus(
        String provider,
        String model,
        boolean chatClientAvailable,
        String vectorStore,
        boolean chromaConfigured,
        int documentCount,
        int chunkCount
) {
}
