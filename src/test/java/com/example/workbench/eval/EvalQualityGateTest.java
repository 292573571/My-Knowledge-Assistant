package com.example.workbench.eval;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class EvalQualityGateTest {

    @Test
    void reportsEveryFailedThreshold() {
        EvalQualityGate gate = new EvalQualityGate(true, 0.8, 0.8, 0.7, 0.05);

        EvalQualityGate.Result result = gate.evaluate(0.7, 3, 0.6, 0.5, 0.1);

        assertThat(result.enabled()).isTrue();
        assertThat(result.passed()).isFalse();
        assertThat(result.failures()).hasSize(4);
    }

    @Test
    void skipsRankingThresholdsWhenNoCaseIsApplicable() {
        EvalQualityGate gate = new EvalQualityGate(true, 0.8, 0.8, 0.7, 0.05);

        EvalQualityGate.Result result = gate.evaluate(1, 0, 0, 0, 0);

        assertThat(result.passed()).isTrue();
    }
}
