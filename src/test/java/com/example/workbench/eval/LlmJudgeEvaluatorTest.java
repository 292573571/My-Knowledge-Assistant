package com.example.workbench.eval;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class LlmJudgeEvaluatorTest {

    private final LlmJudgeEvaluator evaluator = new LlmJudgeEvaluator(null, new ObjectMapper());

    @Test
    void recoversScoreAndPassStateFromTruncatedJson() {
        JudgeResult result = evaluator.parse("""
                {
                  "score": 4,
                  "passed": true,
                  "reason": "回答基本正确
                """);

        assertThat(result.available()).isTrue();
        assertThat(result.score()).isEqualTo(4);
        assertThat(result.passed()).isTrue();
        assertThat(result.reason()).contains("repaired");
    }

    @Test
    void marksUnusableJudgeOutputAsUnavailable() {
        JudgeResult result = evaluator.parse("invalid response");

        assertThat(result.available()).isFalse();
        assertThat(result.score()).isNull();
        assertThat(result.passed()).isFalse();
    }

    @Test
    void usesTheScoreWhenJudgePassFlagIsInconsistent() {
        JudgeResult result = evaluator.parse("""
                {"score": 3, "passed": false, "reason": "部分正确"}
                """);

        assertThat(result.available()).isTrue();
        assertThat(result.passed()).isTrue();
    }
}
