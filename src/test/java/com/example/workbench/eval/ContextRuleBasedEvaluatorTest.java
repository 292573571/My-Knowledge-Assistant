package com.example.workbench.eval;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.workbench.rag.ContextEvaluationResult;
import com.example.workbench.rag.ContextRelation;
import java.util.List;
import org.junit.jupiter.api.Test;

class ContextRuleBasedEvaluatorTest {
    @Test
    void checksRelationStandaloneQuestionAndExpectedQueries() {
        EvalCase evalCase = new EvalCase("context-1", "rag", "follow-up", "它支持什么系统？", false,
                false, false, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                EvalSuite.REGRESSION, EvalLayer.CONTEXT, List.of(), ContextRelation.RELATED,
                "VPN 客户端支持什么系统？", List.of("VPN 客户端支持什么系统？"));
        ContextEvaluationResult actual = new ContextEvaluationResult(ContextRelation.RELATED,
                "VPN 客户端支持什么系统？", List.of("它支持什么系统？", "VPN 客户端支持什么系统？"), List.of());

        assertThat(new ContextRuleBasedEvaluator().evaluate(evalCase, actual).passed()).isTrue();
    }
}
