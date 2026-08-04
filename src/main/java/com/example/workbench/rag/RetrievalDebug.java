package com.example.workbench.rag;

import java.util.List;

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
        String preview,
        String retrievalChannel,
        Double denseScore,
        Double sparseScore,
        Double fusionScore,
        Integer denseRank,
        Integer sparseRank,
        Integer finalRank,
        List<String> matchedQueries
) {
    public RetrievalDebug(
            String question, int topK, double similarityThreshold, String scoreDirection,
            int retrievedChunkCount, boolean usedInContext, String fileName, String headingPath,
            int chunkIndex, Integer pageNumber, double score, String preview
    ) {
        this(question, topK, similarityThreshold, scoreDirection, retrievedChunkCount, usedInContext,
                fileName, headingPath, chunkIndex, pageNumber, score, preview, "DENSE", score, null,
                null, null, null, null, List.of());
    }
}
