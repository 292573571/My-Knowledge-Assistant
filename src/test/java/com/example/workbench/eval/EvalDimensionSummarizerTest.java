package com.example.workbench.eval;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class EvalDimensionSummarizerTest {

    private final EvalDimensionSummarizer summarizer = new EvalDimensionSummarizer();

    @Test
    void groupsResultsBySuiteAndLayer() {
        EvalResult smoke = result("smoke", EvalSuite.SMOKE, EvalLayer.RETRIEVAL, true, true, 1.0, 1.0, 1.0, 1.0);
        EvalResult security = result("security", EvalSuite.SECURITY, EvalLayer.GENERATION, false, false, 0, 0, 0, 0);

        List<EvalDimensionSummary> summaries = summarizer.summarize(List.of(smoke, security));

        assertThat(summaries).extracting(EvalDimensionSummary::suite)
                .containsExactly(EvalSuite.SMOKE, EvalSuite.SECURITY);
        assertThat(summaries.get(0).layer()).isEqualTo(EvalLayer.RETRIEVAL);
        assertThat(summaries.get(0).passRate()).isEqualTo(1.0);
        assertThat(summaries.get(0).mrr()).isEqualTo(1.0);
    }

    private EvalResult result(String id, EvalSuite suite, EvalLayer layer, boolean passed,
                              boolean applicable, double recall, double precision, double mrr, double ndcg) {
        return new EvalResult(id, "rag", "fact", "question", false, true, false, passed,
                passed ? 1 : 0, null, true, passed ? 1 : 0, "reason", "judge", "answer",
                List.of("doc.md"), List.of("doc.md"), List.of(), List.of(), List.of(), List.of(),
                passed, passed, 1, false, false, false, List.of(), suite, layer,
                applicable, recall, precision, mrr, ndcg, applicable ? 1 : null);
    }
}
