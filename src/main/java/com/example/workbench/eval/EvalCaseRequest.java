package com.example.workbench.eval;

import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.constraints.NotBlank;
import java.util.List;

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
        @JsonAlias("forbidden_retrieval_keywords") List<String> forbiddenRetrievalKeywords
) {
    public EvalCaseRequest(
            String caseId, String mode, String type, String question,
            boolean expectNoAnswer, boolean requireLocalEvidence, boolean allowModelFallback,
            List<String> expectedSources, List<String> expectedHeadingPaths,
            List<String> expectedKeywords, List<String> forbiddenKeywords
    ) {
        this(caseId, mode, type, question, expectNoAnswer, requireLocalEvidence, allowModelFallback,
                expectedSources, expectedHeadingPaths, expectedKeywords, forbiddenKeywords,
                List.of(), List.of(), List.of());
    }

    public String mode() {
        return mode == null || mode.isBlank() ? "rag" : mode;
    }
}
