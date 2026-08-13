package com.example.workbench.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class TeachingQualityGateTest {

    private final TeachingQualityGate gate = new TeachingQualityGate();

    @Test
    void passesCompleteReadOnlyTeachingDraft() {
        TeachingQualityAssessment assessment = gate.evaluate(
                "Agent 会围绕目标选择工具，并把工具结果放回上下文后继续决策。例如，先搜索知识库再回答制度问题。",
                "为什么工具结果需要回到上下文中？",
                List.of(new TeachingAgentTrace(1, "searchKnowledge", "SUCCEEDED", 12, null)), true);

        assertThat(assessment.passed()).isTrue();
        assertThat(assessment.score()).isEqualTo(100);
        assertThat(assessment.issues()).isEmpty();
    }

    @Test
    void reportsMissingSearchExampleAndQuestion() {
        TeachingQualityAssessment assessment = gate.evaluate(
                "Agent 是一个可以完成任务的模型。",
                "",
                List.of(), true);

        assertThat(assessment.status()).isEqualTo("NEEDS_REVIEW");
        assertThat(assessment.issues()).containsExactly(
                "没有成功检索当前知识空间", "讲解内容过短", "讲解缺少简短例子", "没有生成理解检查问题");
        assertThat(assessment.score()).isEqualTo(0);
    }
}
