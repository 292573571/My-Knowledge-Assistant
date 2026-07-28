package com.example.workbench.rag;

public record RetrievalDebug(
        String question,
        int topK,
        double similarityThreshold,
        String scoreDirection,
        int retrievedChunkCount,
        boolean usedInContext,
        String fileName,
        String headingPath,
        double score,
        String preview
) {
}
