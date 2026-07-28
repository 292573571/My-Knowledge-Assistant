package com.example.workbench.eval;

public record JudgeResult(
        Integer score,
        boolean passed,
        boolean available,
        String reason
) {
}
