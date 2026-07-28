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
        @JsonAlias("forbidden_keywords") List<String> forbiddenKeywords
) {
    public String mode() {
        return mode == null || mode.isBlank() ? "rag" : mode;
    }
}
