package com.example.workbench.eval;

import java.util.List;

public record EvalSummary(
        int total,
        int passed,
        int failed,
        double passRate,
        List<EvalResult> results
) {
}
