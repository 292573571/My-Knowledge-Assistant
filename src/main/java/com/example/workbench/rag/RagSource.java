package com.example.workbench.rag;

public record RagSource(
        String file,
        int chunkIndex,
        String snippet,
        double score,
        String headingPath,
        String path
) {
    public RagSource(String file, int chunkIndex) {
        this(file, chunkIndex, "", 0.0, null, null);
    }

    public RagSource(String file, int chunkIndex, String snippet, double score) {
        this(file, chunkIndex, snippet, score, null, null);
    }

    public RagSource(String file, int chunkIndex, String snippet, double score, String headingPath) {
        this(file, chunkIndex, snippet, score, headingPath, null);
    }
}
