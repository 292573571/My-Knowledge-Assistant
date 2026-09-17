package com.example.workbench.rag;

public record WorkbenchStatus(
        String provider,
        String model,
        boolean chatClientAvailable,
        String vectorStore,
        boolean chromaConfigured,
        /** Chroma 当前是否真实可达（实测探测，非「Bean 是否存在」）。 */
        boolean chromaReachable,
        int documentCount,
        int chunkCount
) {
}
