package com.example.workbench.eval;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.workbench.rag.RagChatResponse;
import com.example.workbench.rag.RagSource;
import com.example.workbench.rag.RetrievalDebug;
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

    @Test
    void validatesPdfPageAgainstCitationAndRetrievalCandidate() {
        EvalCase evalCase = diagnosticCase("pdf_page", List.of(2), List.of("恢复时间", "十五分钟"), List.of());
        RagSource source = new RagSource("quality-page.pdf", 1, "恢复时间是十五分钟", 0.1, "", "docs/quality-page.pdf", 2);
        RetrievalDebug debug = debug("quality-page.pdf", "", 1, 2, "恢复时间是十五分钟");

        EvalResult result = evaluator.evaluate(evalCase,
                new RagChatResponse("恢复时间是十五分钟。", List.of(source), List.of(debug)));

        assertThat(result.passed()).isTrue();
        assertThat(result.reason()).contains("page number", "retrieval keywords");
    }

    @Test
    void failsWhenPdfCitationUsesWrongPage() {
        EvalCase evalCase = diagnosticCase("pdf_page", List.of(2), List.of(), List.of());
        RagSource source = new RagSource("quality-page.pdf", 0, "恢复时间", 0.1, "", "docs/quality-page.pdf", 1);

        EvalResult result = evaluator.evaluate(evalCase, new RagChatResponse(
                "恢复时间是十五分钟。", List.of(source),
                List.of(debug("quality-page.pdf", "", 0, 1, "恢复时间是十五分钟"))));

        assertThat(result.passed()).isFalse();
        assertThat(result.reason()).contains("page number missing");
    }

    @Test
    void requiresTableHeaderAndRowInSameCandidateAndRejectsHtmlNoise() {
        EvalCase evalCase = diagnosticCase("table", List.of(),
                List.of("服务名称", "恢复目标", "订单服务", "十五分钟"), List.of("产品中心"));
        RetrievalDebug valid = debug("quality-table.html", "数据表", 2, null,
                "| 服务名称 | 恢复目标 |\n| 订单服务 | 十五分钟 |");

        EvalResult passed = evaluator.evaluate(evalCase,
                new RagChatResponse("订单服务恢复目标是十五分钟。",
                        List.of(new RagSource("quality-table.html", 2, "", 0.1, "数据表")), List.of(valid)));
        EvalResult split = evaluator.evaluate(evalCase,
                new RagChatResponse("订单服务恢复目标是十五分钟。",
                        List.of(new RagSource("quality-table.html", 2, "", 0.1, "数据表")),
                        List.of(debug("quality-table.html", "数据表", 1, null, "服务名称 恢复目标"),
                                debug("quality-table.html", "数据表", 2, null, "订单服务 十五分钟"))));

        assertThat(passed.passed()).isTrue();
        assertThat(split.passed()).isFalse();
        assertThat(split.reason()).contains("retrieval keywords missing");
    }

    @Test
    void failsWhenForbiddenNavigationTextEntersRetrievalCandidate() {
        EvalCase evalCase = diagnosticCase("html_noise", List.of(), List.of("恢复时间"), List.of("产品中心"));
        RetrievalDebug debug = debug("quality-clean.html", "运维手册", 1, null,
                "产品中心 订单服务恢复时间是十五分钟");

        EvalResult result = evaluator.evaluate(evalCase,
                new RagChatResponse("恢复时间是十五分钟。",
                        List.of(new RagSource("quality-clean.html", 1, "", 0.1, "运维手册")), List.of(debug)));

        assertThat(result.passed()).isFalse();
        assertThat(result.reason()).contains("forbidden retrieval keywords");
    }

    private EvalCase diagnosticCase(String type, List<Integer> pages, List<String> retrievalKeywords,
                                    List<String> forbiddenRetrievalKeywords) {
        String source = type.equals("pdf_page") ? "quality-page.pdf"
                : type.equals("table") ? "quality-table.html" : "quality-clean.html";
        return new EvalCase("multi-001", "rag", type, "恢复时间是多少？", false, true, false,
                List.of(source), type.equals("pdf_page") ? List.of() : List.of("运维手册", "数据表"),
                List.of("十五分钟"), List.of(), pages, retrievalKeywords, forbiddenRetrievalKeywords);
    }

    private RetrievalDebug debug(String fileName, String headingPath, int chunkIndex, Integer pageNumber,
                                 String preview) {
        return new RetrievalDebug("恢复时间是多少？", 5, 0.85, "distance", 1, true,
                fileName, headingPath, chunkIndex, pageNumber, 0.1, preview);
    }
}
