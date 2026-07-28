package com.example.workbench.eval;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class EvalSummaryMetricsTest {

    @Test
    void exposesAllQualityMetrics() {
        EvalSummary summary = new EvalSummary("20260728-120000-000", 2, 1, 1, 0.5,
                0.5, 0.5, 0.75, 0.5, 0.5, 1.0, List.of());

        assertThat(summary.runId()).isEqualTo("20260728-120000-000");
        assertThat(summary.retrievalHitRate()).isEqualTo(0.5);
        assertThat(summary.citationCorrectnessRate()).isEqualTo(0.5);
        assertThat(summary.keyPointCoverageRate()).isEqualTo(0.75);
        assertThat(summary.unsupportedAnswerRate()).isEqualTo(0.5);
        assertThat(summary.modelFallbackRate()).isEqualTo(0.5);
        assertThat(summary.refusalCorrectnessRate()).isEqualTo(1.0);
    }
}
