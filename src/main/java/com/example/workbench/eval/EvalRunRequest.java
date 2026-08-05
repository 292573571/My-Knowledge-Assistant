package com.example.workbench.eval;

import com.fasterxml.jackson.annotation.JsonAlias;
import java.util.List;

public record EvalRunRequest(@JsonAlias("case_ids") List<Long> caseIds, boolean enhanced, String suite, String layer) {
    public EvalRunRequest(List<Long> caseIds, boolean enhanced) {
        this(caseIds, enhanced, null, null);
    }
}
