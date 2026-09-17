package com.example.workbench.rag;

import java.util.List;

public record RagChatResponse(
        String answer,
        List<RagSource> sources,
        List<RetrievalDebug> retrievalDebug,
        RagAnswerRoute route
) {
    public RagChatResponse(String answer, List<RagSource> sources) {
        this(answer, sources, null, sources == null || sources.isEmpty()
                ? RagAnswerRoute.NO_KNOWLEDGE : RagAnswerRoute.LOCAL_KNOWLEDGE);
    }

    public RagChatResponse(String answer, List<RagSource> sources, List<RetrievalDebug> retrievalDebug) {
        this(answer, sources, retrievalDebug, sources == null || sources.isEmpty()
                ? RagAnswerRoute.NO_KNOWLEDGE : RagAnswerRoute.LOCAL_KNOWLEDGE);
    }
}
