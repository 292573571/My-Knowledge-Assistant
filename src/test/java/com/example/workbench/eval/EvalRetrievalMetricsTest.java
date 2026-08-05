package com.example.workbench.eval;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.workbench.rag.RetrievalDebug;
import java.util.List;
import org.junit.jupiter.api.Test;

class EvalRetrievalMetricsTest {

    private final EvalRetrievalMetrics metrics = new EvalRetrievalMetrics();

    @Test
    void calculatesDocumentLevelTopFiveMetricsAndDeduplicatesChunks() {
        EvalCase evalCase = caseWithSources(List.of("target.md", "other.md"));
        List<RetrievalDebug> candidates = List.of(
                candidate("noise.md", 1, 0),
                candidate("target.md", 2, 0),
                candidate("target.md", 3, 1),
                candidate("other.md", 4, 0)
        );

        EvalRetrievalMetrics.Metrics result = metrics.calculate(evalCase, candidates);

        assertThat(result.applicable()).isTrue();
        assertThat(result.recallAt5()).isEqualTo(1.0);
        assertThat(result.precisionAt5()).isEqualTo(0.4);
        assertThat(result.reciprocalRank()).isEqualTo(0.5);
        assertThat(result.firstRelevantRank()).isEqualTo(2);
        assertThat(result.ndcgAt5()).isBetween(0.69, 0.70);
    }

    @Test
    void excludesNoAnswerAndCasesWithoutExpectedSources() {
        EvalCase noSources = caseWithSources(List.of());
        EvalCase noAnswer = new EvalCase("no-answer", "rag", "refusal", "question", true,
                false, false, List.of("target.md"), List.of(), List.of(), List.of());

        assertThat(metrics.calculate(noSources, List.of()).applicable()).isFalse();
        assertThat(metrics.calculate(noAnswer, List.of(candidate("target.md", 1, 0))).applicable()).isFalse();
    }

    private EvalCase caseWithSources(List<String> sources) {
        return new EvalCase("case", "rag", "fact", "question", false, true, false,
                sources, List.of(), List.of(), List.of());
    }

    private RetrievalDebug candidate(String fileName, int finalRank, int chunkIndex) {
        return new RetrievalDebug("question", 5, 1, "distance", 4, true, fileName, "heading",
                chunkIndex, null, 0.1, "preview", "HYBRID", 0.1, 0.1, 0.1,
                finalRank, finalRank, finalRank, List.of("question"));
    }
}
