package com.example.workbench.eval;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.workbench.rag.RagChatResponse;
import com.example.workbench.rag.RagSource;
import java.util.List;
import org.junit.jupiter.api.Test;

class RuleBasedEvaluatorTest {

    private final RuleBasedEvaluator evaluator = new RuleBasedEvaluator();

    @Test
    void passesWhenAnswerMatchesSourceHeadingAndKeywords() {
        EvalCase evalCase = new EvalCase(
                "rag-001",
                "rag",
                "fact",
                "RAG 是什么？",
                false,
                true,
                false,
                List.of("rag-notes.md"),
                List.of("RAG > Overview"),
                List.of("检索", "生成"),
                List.of("错误答案")
        );
        RagChatResponse response = new RagChatResponse(
                "RAG 通过检索相关上下文来增强生成。",
                List.of(new RagSource("rag-notes.md", 0, "", 0.1, "RAG > Overview"))
        );

        EvalResult result = evaluator.evaluate(evalCase, response);

        assertThat(result.passed()).isTrue();
        assertThat(result.finalScore()).isEqualTo(1.0);
        assertThat(result.matchedKeywords()).containsExactly("检索", "生成");
    }

    @Test
    void failsWhenForbiddenKeywordAppears() {
        EvalCase evalCase = new EvalCase(
                "rag-002",
                "rag",
                "fact",
                "RAG 是什么？",
                false,
                true,
                false,
                List.of("rag-notes.md"),
                List.of("RAG"),
                List.of("检索"),
                List.of("不需要检索")
        );
        RagChatResponse response = new RagChatResponse(
                "RAG 不需要检索。",
                List.of(new RagSource("rag-notes.md", 0, "", 0.1, "RAG"))
        );

        EvalResult result = evaluator.evaluate(evalCase, response);

        assertThat(result.passed()).isFalse();
        assertThat(result.reason()).contains("forbidden keywords");
    }

    @Test
    void passesNoAnswerCaseWhenAnswerIsConservative() {
        EvalCase evalCase = new EvalCase(
                "rag-003",
                "rag",
                "no_answer",
                "知识库没有的问题",
                true,
                false,
                false,
                List.of(),
                List.of(),
                List.of(),
                List.of("确定是")
        );
        RagChatResponse response = new RagChatResponse("知识库没有足够信息，无法确认。", List.of());

        EvalResult result = evaluator.evaluate(evalCase, response);

        assertThat(result.passed()).isTrue();
        assertThat(result.reason()).contains("no-answer expression");
    }

    @Test
    void recognizesTheStandardRagNoContextAnswer() {
        EvalCase evalCase = new EvalCase(
                "rag-004",
                "rag",
                "no_answer",
                "知识库没有的问题",
                true,
                false,
                false,
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );
        RagChatResponse response = new RagChatResponse(
                "我在当前知识库中没有找到足够信息和依据来回答这个问题。",
                List.of()
        );

        EvalResult result = evaluator.evaluate(evalCase, response);

        assertThat(result.passed()).isTrue();
        assertThat(result.reason()).contains("no-answer expression");
    }

    @Test
    void failsWhenRequiredLocalEvidenceIsMissing() {
        EvalCase evalCase = new EvalCase(
                "rag-005", "rag", "fact", "需要本地证据的问题", false, true, false,
                List.of("flow.md"), List.of("数据流"), List.of("答案"), List.of()
        );

        EvalResult result = evaluator.evaluate(evalCase, new RagChatResponse("这是答案。", List.of()));

        assertThat(result.passed()).isFalse();
        assertThat(result.reason()).contains("required local evidence missing");
        assertThat(result.unsupportedAnswer()).isTrue();
    }

    @Test
    void failsWhenModelFallbackIsNotAllowed() {
        EvalCase evalCase = new EvalCase(
                "rag-006", "rag", "fact", "不允许模型补充的问题", false, false, false,
                List.of(), List.of(), List.of("答案"), List.of()
        );
        String fallbackAnswer = "这是答案。\n\n以上回答基于通用大模型知识，不是当前知识库内容。";

        EvalResult result = evaluator.evaluate(evalCase, new RagChatResponse(fallbackAnswer, List.of()));

        assertThat(result.passed()).isFalse();
        assertThat(result.reason()).contains("model fallback not allowed");
        assertThat(result.modelFallbackUsed()).isTrue();
    }
}
