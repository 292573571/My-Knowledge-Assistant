package com.example.workbench.learningassistant;

import com.example.workbench.agent.TeachingAgentResult;
import com.example.workbench.agent.TeachingCheckResponse;
import com.example.workbench.agent.TeachingPracticeResponse;
import com.example.workbench.agent.TeachingSessionSummary;
import com.example.workbench.rag.RagSource;
import java.util.List;

public record LearningAssistantResponse(
        String sessionId,
        String messageId,
        String answer,
        LearningMode mode,
        LearningIntent intent,
        String topic,
        String stage,
        String nextAction,
        Object check,
        Object practice,
        TeachingSessionSummary progress,
        List<RagSource> sources,
        List<?> traces,
        boolean readOnly
) {
    public static LearningAssistantResponse chat(String sessionId, com.example.workbench.workbench.WorkbenchChatResponse response) {
        return new LearningAssistantResponse(sessionId, response.messageId(), response.answer(), LearningMode.CHAT,
                LearningIntent.ANSWER, null, "CHAT", null, null, null, null,
                response.sources().stream().map(source -> (RagSource) source).toList(), response.toolCalls(), false);
    }

    public static LearningAssistantResponse teaching(TeachingAgentResult result, LearningIntent intent) {
        return new LearningAssistantResponse(result.sessionId(), null, result.answer(), LearningMode.GUIDED, intent,
                result.topic(), result.stage().name(), result.nextAction().name(), result.check(), null,
                result.sessionSummary(), result.sources(), result.traces(), result.readOnly());
    }

    public static LearningAssistantResponse check(String sessionId, TeachingCheckResponse result) {
        return new LearningAssistantResponse(sessionId, result.attemptId(), result.feedback(), LearningMode.REVIEW,
                LearningIntent.SUBMIT_CHECK, result.topic(), result.stage().name(), result.nextAction().name(), result,
                result.practice(), result.sessionSummary(), List.of(), List.of(), result.readOnly());
    }

    public static LearningAssistantResponse practice(String sessionId, TeachingPracticeResponse result) {
        return new LearningAssistantResponse(sessionId, result.practiceId(), result.feedback(), LearningMode.PRACTICE,
                LearningIntent.START_PRACTICE, result.topic(), result.stage().name(), result.nextAction().name(), null,
                result, result.sessionSummary(), List.of(), List.of(), result.readOnly());
    }
}
