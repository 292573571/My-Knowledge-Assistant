package com.example.workbench.workbench;

import com.example.workbench.rag.RagAnswerRoute;
import java.util.List;

public record WorkbenchChatResponse(
        String messageId,
        String answer,
        List<?> sources,
        List<?> toolCalls,
        RagAnswerRoute route
) {
    public WorkbenchChatResponse(String messageId, String answer, List<?> sources, List<?> toolCalls) {
        this(messageId, answer, sources, toolCalls, sources == null || sources.isEmpty()
                ? RagAnswerRoute.NO_KNOWLEDGE : RagAnswerRoute.LOCAL_KNOWLEDGE);
    }
}
