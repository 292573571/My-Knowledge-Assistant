package com.example.workbench.rag;

/**
 * 对外暴露的检索来源。documentId/category 供前端跳转与分组；
 * 历史兼容：旧调用方通过下方便捷构造器继续工作。
 */
public record RagSource(
        String file,
        int chunkIndex,
        String snippet,
        double score,
        String headingPath,
        String path,
        Integer pageNumber,
        String documentId,
        String category
) {
    public RagSource(
            String file, int chunkIndex, String snippet, double score, String headingPath, String path
    ) {
        this(file, chunkIndex, snippet, score, headingPath, path, null, null, null);
    }

    public RagSource(String file, int chunkIndex, String snippet, double score, String headingPath) {
        this(file, chunkIndex, snippet, score, headingPath, null, null, null, null);
    }

    public RagSource(String file, int chunkIndex, String snippet, double score, String headingPath,
                     String path, Integer pageNumber) {
        this(file, chunkIndex, snippet, score, headingPath, path, pageNumber, null, null);
    }
}
