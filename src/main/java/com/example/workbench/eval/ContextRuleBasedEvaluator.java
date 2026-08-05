package com.example.workbench.eval;

import com.example.workbench.rag.ContextEvaluationResult;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * 评估上下文关系、独立问题和最终查询规划。
 */
@Component
public class ContextRuleBasedEvaluator {

    /**
     * 评估一个上下文用例。
     *
     * @param evalCase 上下文评测用例
     * @param actual 实际上下文规划结果
     * @return 统一的评测结果快照
     */
    public EvalResult evaluate(EvalCase evalCase, ContextEvaluationResult actual) {
        boolean relationMatched = evalCase.expectedRelation() == null
                || evalCase.expectedRelation() == actual.relation();
        boolean standaloneMatched = evalCase.expectedStandaloneQuestion() == null
                || normalize(evalCase.expectedStandaloneQuestion()).equals(normalize(actual.standaloneQuestion()));
        boolean queriesMatched = evalCase.expectedRetrievalQueries().stream().allMatch(expected ->
                actual.retrievalQueries().stream().anyMatch(query -> normalize(query).contains(normalize(expected))));
        boolean passed = relationMatched && standaloneMatched && queriesMatched;
        String reason = "relation=" + actual.relation() + ", standalone="
                + (actual.standaloneQuestion() == null ? "-" : actual.standaloneQuestion())
                + ", queries=" + actual.retrievalQueries().size();
        return new EvalResult(evalCase.id(), evalCase.mode(), evalCase.type(), evalCase.question(), false,
                false, false, passed, passed ? 1 : 0, null, true, passed ? 1 : 0, reason, "Not applicable",
                actual.standaloneQuestion() == null ? "" : actual.standaloneQuestion(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), false, false, 1, false, false, false, List.of(),
                evalCase.suite(), evalCase.layer(), false, 0, 0, 0, 0, null);
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }
}
