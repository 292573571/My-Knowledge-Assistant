package com.example.workbench.rag;

public record RagSource(
        String file,
        int chunkIndex,
        String snippet,
        double score,
        String headingPath,
        String path,
        Integer pageNumber
) {
    public RagSource(
            String file, int chunkIndex, String snippet, double score, String headingPath, String path
    ) {
        this(file, chunkIndex, snippet, score, headingPath, path, null);
    }

    public RagSource(String file, int chunkIndex, String snippet, double score, String headingPath) {
        this(file, chunkIndex, snippet, score, headingPath, null, null);
    }
}
