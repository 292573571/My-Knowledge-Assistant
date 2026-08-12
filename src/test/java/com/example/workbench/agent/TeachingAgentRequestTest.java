package com.example.workbench.agent;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TeachingAgentRequestTest {

    @Test
    void appliesSafeDefaultsWithoutLettingTheModelChooseIdentityOrWorkspace() {
        TeachingAgentRequest request = new TeachingAgentRequest(
                "workspace-a", "  ", "Agent", null, "请解释 Agent");

        assertThat(request.normalizedSessionId()).isEqualTo("default");
        assertThat(request.normalizedUserLevel()).isEqualTo(TeachingUserLevel.BEGINNER);
        assertThat(TeachingAgentContext.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .contains("user", "access", "sessionId", "topic", "stage", "userLevel");
    }
}
