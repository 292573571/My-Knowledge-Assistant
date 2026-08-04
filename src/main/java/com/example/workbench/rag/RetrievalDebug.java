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
        int chunkIndex,
        Integer pageNumber,
        double score,
        String preview
) {
}
