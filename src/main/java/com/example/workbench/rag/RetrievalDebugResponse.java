package com.example.workbench.rag;

import java.util.List;

public record RetrievalDebugResponse(
        String question,
        List<String> queries,
        List<RetrievalDebug> candidates
) {
}
