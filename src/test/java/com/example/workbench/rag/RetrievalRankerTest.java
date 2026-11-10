package com.example.workbench.rag;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.workbench.workspace.DocumentVisibility;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 抽取出来的排序/阈值规则必须与原实现逐条一致。
 * 这些数字（+8/+6/+2、-12、SOURCE=4）都是业务口径，改动必须走评审而不是重构顺手调。
 */
class RetrievalRankerTest {

    @Test
    void structuredMatchRewardsFileNameHeadingAndTableChunks() {
        SourceDocument source = document("季度对账表.pdf", "RAG > Chunk", "table", 0.5);

        int score = RetrievalRanker.structuredMatchScore("请解释 季度对账表.pdf 里 RAG > Chunk 的表格", source);

        assertThat(score).isEqualTo(8 + 6 + 2);
    }

    @Test
    void lexicalMatchFindsChineseWordInsideGreedyTerm() {
        SourceDocument source = document("a.pdf", "", "text-paragraph", 0.5,
                "缓存是提升命中率的手段，需要设置过期时间。");

        assertThat(LexicalScorer.matchScore("什么是缓存", source.content())).isGreaterThan(0);
    }

    @Test
    void documentPriorityKeepsSourceAndFormalNoteOrdering() {
        assertThat(RetrievalRanker.documentPriority(document("a.pdf", "", "text-paragraph", 0.5, "正文", "SOURCE")))
                .isEqualTo(4);
        assertThat(RetrievalRanker.documentPriority(document("a.pdf", "", "text-paragraph", 0.5, "正文", "FORMAL_NOTE")))
                .isEqualTo(2);
        assertThat(RetrievalRanker.documentPriority(document("a.pdf", "", "text-paragraph", 0.5, "正文", "GENERAL")))
                .isZero();
    }

    @Test
    void learningRecordsArePenalised() {
        SourceDocument learningRecord = new SourceDocument(
                "id", "今天学习了 RAG 检索", "学习记录", "s", "docs/learning-records/2026-11-10.md", 0,
                "doc-1", "2026-11-10.md", "", 0.5, "", 0, 0, 10, "text-paragraph",
                "LEARNING_RECORD", "1", "public-default", DocumentVisibility.PRIVATE, 0);

        assertThat(RetrievalRanker.documentPriority(learningRecord)).isEqualTo(-4);
    }

    @Test
    void thresholdRespectsScoreDirection() {
        SourceDocument source = document("a.pdf", "", "text-paragraph", 0.4);

        assertThat(RetrievalRanker.passesThreshold(source, null, "distance", 0.72)).isTrue();
        assertThat(RetrievalRanker.passesThreshold(source, null, "similarity", 0.72)).isFalse();
        assertThat(RetrievalRanker.passesThreshold(source, null, "similarity", 0.3)).isTrue();
    }

    @Test
    void denseSparseCandidateAlwaysPassesThreshold() {
        SourceDocument source = document("a.pdf", "", "text-paragraph", 0.99);
        CandidateTrace sparseTrace = new CandidateTrace("SPARSE", 0.99, 3.5, 1.0, null, 1, 1, List.of("q"));

        assertThat(RetrievalRanker.passesThreshold(source, sparseTrace, "distance", 0.72)).isTrue();
    }

    @Test
    void diversifyCapsChunksPerDocumentAndTopK() {
        List<SourceDocument> ranked = List.of(
                documentWithDocumentId("d1", 0),
                documentWithDocumentId("d1", 1),
                documentWithDocumentId("d1", 2),
                documentWithDocumentId("d2", 0));

        List<SourceDocument> selected = RetrievalRanker.diversify(ranked, 3, 2);

        assertThat(selected).hasSize(3);
        assertThat(selected.stream().filter(item -> "d1".equals(item.documentId())).count()).isEqualTo(2);
    }

    @Test
    void sourceBoostStillRewardsChunkHeadingForChunkQuestions() {
        SourceDocument source = document("a.pdf", "RAG > Chunk", "text-paragraph", 0.5,
                "文档片段 检索质量");

        assertThat(RetrievalRanker.sourceBoost("RAG 的 Chunk 怎么切分", source)).isEqualTo(8 + 3);
    }

    private SourceDocument document(String fileName, String headingPath, String chunkType, double score) {
        return document(fileName, headingPath, chunkType, score, "正文内容足够长，用于解释这个概念。");
    }

    private SourceDocument document(String fileName, String headingPath, String chunkType, double score, String content) {
        return document(fileName, headingPath, chunkType, score, content, "SOURCE");
    }

    private SourceDocument document(String fileName, String headingPath, String chunkType, double score,
                                    String content, String category) {
        return new SourceDocument("id", content, fileName, "s", "p", 0,
                "doc-" + fileName, fileName, "", score, headingPath, 0, 0, content.length(),
                chunkType, category, "", "public-default", DocumentVisibility.PUBLIC, 0);
    }

    private SourceDocument documentWithDocumentId(String documentId, int chunkIndex) {
        return new SourceDocument("id-" + documentId + "-" + chunkIndex, "正文", "t", "s", "p", chunkIndex,
                documentId, "f.pdf", "", 0.5, "", 0, 0, 2, "text-paragraph", "SOURCE", "",
                "public-default", DocumentVisibility.PUBLIC, 0);
    }
}
