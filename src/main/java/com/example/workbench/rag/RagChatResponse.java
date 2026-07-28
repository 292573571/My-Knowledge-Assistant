package com.example.workbench.rag;

import java.util.List;

public record RagChatResponse(
        String answer,
        List<RagSource> sources,
        List<RetrievalDebug> retrievalDebug
) {
    public RagChatResponse(String answer, List<RagSource> sources) {
        this(answer, sources, null);
    }
}
