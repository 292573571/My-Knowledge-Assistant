package com.example.workbench.agent;

import com.example.workbench.rag.RagSource;
import java.util.List;

public record TeachingAgentResult(
        String answer,
        String sessionId,
        String topic,
        TeachingStage stage,
        TeachingNextAction nextAction,
        TeachingCheckPrompt check,
        List<RagSource> sources,
        List<TeachingAgentTrace> traces,
        int steps,
        boolean readOnly
) {
}
