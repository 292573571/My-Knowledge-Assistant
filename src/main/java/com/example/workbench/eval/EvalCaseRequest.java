package com.example.workbench.eval;

import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import com.example.workbench.memory.ChatMessage;
import com.example.workbench.rag.ContextRelation;

public record EvalCaseRequest(
        @JsonAlias("case_id") String caseId,
        String mode,
        @NotBlank String type,
        @NotBlank String question,
        @JsonAlias("expect_no_answer") boolean expectNoAnswer,
        @JsonAlias("require_local_evidence") boolean requireLocalEvidence,
        @JsonAlias("allow_model_fallback") boolean allowModelFallback,
        @JsonAlias("expected_sources") List<String> expectedSources,
        @JsonAlias("expected_heading_paths") List<String> expectedHeadingPaths,
        @JsonAlias("expected_keywords") List<String> expectedKeywords,
        @JsonAlias("forbidden_keywords") List<String> forbiddenKeywords,
        @JsonAlias("expected_page_numbers") List<Integer> expectedPageNumbers,
        @JsonAlias("expected_retrieval_keywords") List<String> expectedRetrievalKeywords,
        @JsonAlias("forbidden_retrieval_keywords") List<String> forbiddenRetrievalKeywords,
        @JsonAlias("suite_name") String suite,
        @JsonAlias("layer_name") String layer,
        List<ChatMessage> history,
        @JsonAlias("expected_relation") ContextRelation expectedRelation,
        @JsonAlias("expected_standalone_question") String expectedStandaloneQuestion,
        @JsonAlias("expected_retrieval_queries") List<String> expectedRetrievalQueries
) {
    public EvalCaseRequest(
            String caseId, String mode, String type, String question,
            boolean expectNoAnswer, boolean requireLocalEvidence, boolean allowModelFallback,
            List<String> expectedSources, List<String> expectedHeadingPaths,
            List<String> expectedKeywords, List<String> forbiddenKeywords,
            List<Integer> expectedPageNumbers, List<String> expectedRetrievalKeywords,
            List<String> forbiddenRetrievalKeywords
    ) {
        this(caseId, mode, type, question, expectNoAnswer, requireLocalEvidence, allowModelFallback,
                expectedSources, expectedHeadingPaths, expectedKeywords, forbiddenKeywords,
                expectedPageNumbers, expectedRetrievalKeywords, forbiddenRetrievalKeywords, null, null, List.of(), null, null, List.of());
    }

    public EvalCaseRequest(
            String caseId, String mode, String type, String question,
            boolean expectNoAnswer, boolean requireLocalEvidence, boolean allowModelFallback,
            List<String> expectedSources, List<String> expectedHeadingPaths,
            List<String> expectedKeywords, List<String> forbiddenKeywords
    ) {
        this(caseId, mode, type, question, expectNoAnswer, requireLocalEvidence, allowModelFallback,
                expectedSources, expectedHeadingPaths, expectedKeywords, forbiddenKeywords,
                List.of(), List.of(), List.of(), null, null, List.of(), null, null, List.of());
    }

    public String mode() {
        return mode == null || mode.isBlank() ? "rag" : mode;
    }

    /**
     * 返回兼容旧请求的规范化评测集合。
     *
     * @return 规范化评测集合
     */
    public EvalSuite normalizedSuite() {
        return EvalSuite.from(suite);
    }

    /**
     * 返回兼容旧请求的规范化评测层级。
     *
     * @return 规范化评测层级
     */
    public EvalLayer normalizedLayer() {
        return EvalLayer.from(layer);
    }
}
