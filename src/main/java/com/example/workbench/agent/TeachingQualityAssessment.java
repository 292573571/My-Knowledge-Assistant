package com.example.workbench.agent;

import java.util.List;

public record TeachingQualityAssessment(String status, int score, List<String> issues) {

    public TeachingQualityAssessment {
        issues = issues == null ? List.of() : List.copyOf(issues);
    }

    public boolean passed() {
        return "PASS".equals(status);
    }
}
