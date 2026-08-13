package com.example.workbench.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class JpaTeachingAttemptStoreTest {

    @Test
    void roundTripsCompletedCheckAndPracticeResponsesThroughJsonSnapshots() {
        TeachingAttemptRepository repository = mock(TeachingAttemptRepository.class);
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        JpaTeachingAttemptStore store = new JpaTeachingAttemptStore(repository, objectMapper);
        TeachingAttemptState state = new TeachingAttemptState(
                "check-1", "id:1", "workspace-a", "session-1", "Agent", "Agent 为什么需要工具？",
                Instant.parse("2026-08-13T10:30:00Z"), Instant.parse("2026-08-13T10:00:00Z"));
        state.answer = "Agent 根据目标调用工具，结果回到上下文。例如检索资料。";
        state.checkCompleted = true;
        state.practiceId = "practice-1";
        state.practiceQuestion = "请描述一个任务。";
        state.practiceAnswer = "目标是回答问题，调用工具检索资料，结果影响下一步决策。";
        state.practiceCompleted = true;
        state.response = new TeachingCheckResponse("check-1", "session-1", "Agent", TeachingStage.CHECK,
                TeachingNextAction.PRACTICE, 5, 5, true, "通过", null,
                new TeachingPracticePrompt("practice-1", "请描述一个任务。", state.expiresAt,
                        TeachingPracticeStatus.PENDING), null, true, "2026-08-13", false);
        state.practiceResponse = new TeachingPracticeResponse("practice-1", "session-1", "Agent", "请描述一个任务。",
                TeachingPracticeStatus.COMPLETED, TeachingStage.PRACTICE, TeachingNextAction.COMPLETE,
                5, 5, true, "通过", null, null, true, "2026-08-13", true);

        store.save(state);
        var saved = org.mockito.ArgumentCaptor.forClass(TeachingAttemptEntity.class);
        org.mockito.Mockito.verify(repository).save(saved.capture());
        when(repository.findById("check-1")).thenReturn(Optional.of(saved.getValue()));

        TeachingAttemptState restored = store.findByCheckId("check-1").orElseThrow();

        assertThat(restored.workspaceId).isEqualTo("workspace-a");
        assertThat(restored.checkCompleted).isTrue();
        assertThat(restored.response).isEqualTo(state.response);
        assertThat(restored.practiceId).isEqualTo("practice-1");
        assertThat(restored.practiceCompleted).isTrue();
        assertThat(restored.practiceResponse).isEqualTo(state.practiceResponse);
    }

    @Test
    void inMemoryStoreRemovesExpiredAttemptsWithoutTouchingAnotherWorkspace() {
        InMemoryTeachingAttemptStore store = new InMemoryTeachingAttemptStore();
        Instant now = Instant.parse("2026-08-13T10:00:00Z");
        store.save(new TeachingAttemptState("expired", "id:1", "workspace-a", "session-1", "Agent", "问题？",
                now.minusSeconds(1), now.minusSeconds(1801)));
        store.save(new TeachingAttemptState("active", "id:1", "workspace-b", "session-1", "Agent", "问题？",
                now.plusSeconds(1800), now));

        store.deleteExpired(now);

        assertThat(store.findByCheckId("expired")).isEmpty();
        assertThat(store.findByCheckId("active")).isPresent();
    }
}
