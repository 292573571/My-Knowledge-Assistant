package com.example.workbench.learningassistant;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.workbench.agent.TeachingCheckPrompt;
import com.example.workbench.agent.TeachingNextAction;
import com.example.workbench.agent.TeachingStage;
import com.example.workbench.agent.TeachingUserLevel;
import com.example.workbench.rag.RagSource;
import com.example.workbench.workbench.WorkbenchChatResponse;
import java.util.List;
import org.junit.jupiter.api.Test;

class LearningAssistantResponseTest {

    @Test
    void mapsNormalChatToAnswerResponse() {
        RagSource source = new RagSource("guide.md", 1, "正文", 0.9, "");
        LearningAssistantResponse response = LearningAssistantResponse.chat("session-1",
                new WorkbenchChatResponse("message-1", "回答", List.of(source), List.of()));

        assertThat(response.sessionId()).isEqualTo("session-1");
        assertThat(response.intent()).isEqualTo(LearningIntent.ANSWER);
        assertThat(response.mode()).isEqualTo(LearningMode.CHAT);
        assertThat(response.stage()).isEqualTo("CHAT");
        assertThat(response.sources()).containsExactly(source);
    }

    @Test
    void acceptsExplicitTeachingIntentAndPreservesCheckPrompt() {
        TeachingCheckPrompt prompt = new TeachingCheckPrompt("check-1", "请解释核心概念？");

        assertThat(prompt.checkId()).isEqualTo("check-1");
        assertThat(prompt.question()).contains("核心概念");
        assertThat(TeachingStage.EXPLAIN.name()).isEqualTo("EXPLAIN");
        assertThat(TeachingNextAction.CHECK.name()).isEqualTo("CHECK");
        assertThat(TeachingUserLevel.BEGINNER.name()).isEqualTo("BEGINNER");
    }
}
