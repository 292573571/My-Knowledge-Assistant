package com.example.workbench.eval;

import com.example.workbench.rag.RagChatResponse;
import com.example.workbench.rag.RetrievalDebug;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class RuleBasedEvaluator {

    public EvalResult evaluate(EvalCase evalCase, RagChatResponse response) {
        String answer = response.answer() == null ? "" : response.answer();
        List<String> actualSources = response.sources().stream().map(source -> source.file()).toList();
        List<String> actualHeadingPaths = response.sources().stream()
                .map(source -> source.headingPath() == null ? "" : source.headingPath())
                .filter(value -> !value.isBlank())
                .toList();
        List<String> matchedKeywords = matchedKeywords(answer, nullSafe(evalCase.expectedKeywords()));
        List<String> missingKeywords = nullSafe(evalCase.expectedKeywords()).stream()
                .filter(keyword -> !matchedKeywords.contains(keyword))
                .toList();

        if (evalCase.expectNoAnswer()) {
            return evaluateNoAnswerCase(
                    evalCase,
                    answer,
                    actualSources,
                    actualHeadingPaths,
                    matchedKeywords,
                    missingKeywords,
                    response.retrievalDebug()
            );
        }

        boolean hasAnswer = !answer.isBlank();
        boolean sourceMatched = anyContains(actualSources, nullSafe(evalCase.expectedSources()));
        boolean headingPathMatched = anyContains(actualHeadingPaths, nullSafe(evalCase.expectedHeadingPaths()));
        boolean keywordMatched = matchedKeywords.size() >= Math.ceil(nullSafe(evalCase.expectedKeywords()).size() / 2.0);
        boolean forbiddenMatched = nullSafe(evalCase.forbiddenKeywords()).stream().anyMatch(answer::contains);

        double score = 0;
        score += hasAnswer ? 0.2 : 0;
        score += sourceMatched ? 0.3 : 0;
        score += headingPathMatched ? 0.3 : 0;
        score += keywordMatched ? 0.2 : 0;

        if (forbiddenMatched) {
            score = Math.max(0, score - 0.3);
        }

        boolean passed = score >= 0.7 && !forbiddenMatched;
        String reason = reason(sourceMatched, headingPathMatched, keywordMatched, forbiddenMatched);

        return new EvalResult(
                evalCase.id(),
                evalCase.mode(),
                evalCase.type(),
                evalCase.question(),
                evalCase.expectNoAnswer(),
                passed,
                score,
                null,
                true,
                score,
                reason,
                "Judge disabled",
                answer,
                nullSafe(evalCase.expectedSources()),
                actualSources,
                nullSafe(evalCase.expectedHeadingPaths()),
                actualHeadingPaths,
                matchedKeywords,
                missingKeywords,
                response.retrievalDebug()
        );
    }

    private EvalResult evaluateNoAnswerCase(
            EvalCase evalCase,
            String answer,
            List<String> actualSources,
            List<String> actualHeadingPaths,
            List<String> matchedKeywords,
            List<String> missingKeywords,
            List<RetrievalDebug> retrievalDebug
    ) {
        boolean hasAnswer = !answer.isBlank();
        boolean noAnswerMatched = expressesNoAnswer(answer);
        boolean forbiddenMatched = nullSafe(evalCase.forbiddenKeywords()).stream().anyMatch(answer::contains);

        double score = 0;
        score += hasAnswer ? 0.3 : 0;
        score += noAnswerMatched ? 0.7 : 0;

        if (forbiddenMatched) {
            score = Math.max(0, score - 0.5);
        }

        boolean passed = score >= 0.7 && !forbiddenMatched;
        String reason = noAnswerReason(hasAnswer, noAnswerMatched, forbiddenMatched);

        return new EvalResult(
                evalCase.id(),
                evalCase.mode(),
                evalCase.type(),
                evalCase.question(),
                evalCase.expectNoAnswer(),
                passed,
                score,
                null,
                true,
                score,
                reason,
                "Judge disabled",
                answer,
                nullSafe(evalCase.expectedSources()),
                actualSources,
                nullSafe(evalCase.expectedHeadingPaths()),
                actualHeadingPaths,
                matchedKeywords,
                missingKeywords,
                retrievalDebug
        );
    }

    private boolean anyContains(List<String> actualValues, List<String> expectedValues) {
        return expectedValues.stream()
                .anyMatch(expected -> actualValues.stream()
                        .anyMatch(actual -> actual.contains(expected) || expected.contains(actual)));
    }

    private List<String> matchedKeywords(String answer, List<String> expectedKeywords) {
        String normalizedAnswer = answer.toLowerCase();
        return expectedKeywords.stream()
                .filter(keyword -> normalizedAnswer.contains(keyword.toLowerCase()))
                .toList();
    }

    private boolean expressesNoAnswer(String answer) {
        String normalizedAnswer = answer.toLowerCase();
        return List.of(
                "没有找到相关信息",
                "没有足够信息",
                "没有找到足够信息",
                "知识库没有",
                "无法确认",
                "不能确认",
                "不知道",
                "未找到",
                "not found",
                "no relevant information",
                "cannot confirm"
        ).stream().anyMatch(normalizedAnswer::contains);
    }

    private List<String> nullSafe(List<String> values) {
        return values == null ? List.of() : values;
    }

    private String reason(boolean sourceMatched, boolean headingPathMatched, boolean keywordMatched, boolean forbiddenMatched) {
        List<String> parts = new ArrayList<>();

        if (sourceMatched) {
            parts.add("source");
        }

        if (headingPathMatched) {
            parts.add("headingPath");
        }

        if (keywordMatched) {
            parts.add("keywords");
        }

        if (forbiddenMatched) {
            parts.add("forbidden keywords");
        }

        if (parts.isEmpty()) {
            return "No rule matched";
        }

        if (parts.size() == 1) {
            return "Matched " + parts.get(0);
        }

        return "Matched " + String.join(", ", parts.subList(0, parts.size() - 1))
                + " and "
                + parts.get(parts.size() - 1);
    }

    private String noAnswerReason(boolean hasAnswer, boolean noAnswerMatched, boolean forbiddenMatched) {
        List<String> parts = new ArrayList<>();

        if (hasAnswer) {
            parts.add("answer");
        }

        if (noAnswerMatched) {
            parts.add("no-answer expression");
        }

        if (forbiddenMatched) {
            parts.add("forbidden keywords");
        }

        if (parts.isEmpty()) {
            return "No rule matched";
        }

        if (parts.size() == 1) {
            return "Matched " + parts.get(0);
        }

        return "Matched " + String.join(", ", parts.subList(0, parts.size() - 1))
                + " and "
                + parts.get(parts.size() - 1);
    }
}
