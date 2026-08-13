package com.example.workbench.agent;

import java.util.ArrayList;
import java.util.List;

public class TeachingQualityGate {

    private static final int MIN_EXPLANATION_LENGTH = 40;

    public TeachingQualityAssessment evaluate(String explanation, String checkQuestion,
                                               List<TeachingAgentTrace> traces, boolean readOnly) {
        List<String> issues = new ArrayList<>();
        String answer = explanation == null ? "" : explanation.strip();
        String question = checkQuestion == null ? "" : checkQuestion.strip();
        boolean searched = traces != null && traces.stream()
                .anyMatch(trace -> "searchKnowledge".equals(trace.toolName())
                        && "SUCCEEDED".equals(trace.status()));
        if (!searched) issues.add("没有成功检索当前知识空间");
        if (answer.length() < MIN_EXPLANATION_LENGTH) issues.add("讲解内容过短");
        if (!containsExample(answer)) issues.add("讲解缺少简短例子");
        if (question.isBlank() || !question.matches(".*[?？].*")) issues.add("没有生成理解检查问题");
        if (!readOnly) issues.add("教学 Agent 未处于只读模式");
        int score = Math.max(0, 100 - issues.size() * 25);
        return new TeachingQualityAssessment(issues.isEmpty() ? "PASS" : "NEEDS_REVIEW", score, issues);
    }

    private boolean containsExample(String answer) {
        return answer.contains("例如") || answer.contains("比如") || answer.contains("案例")
                || answer.contains("场景") || answer.contains("举例");
    }
}
