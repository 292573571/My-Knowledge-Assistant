package com.example.workbench.learningassistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.workbench.workbench.WorkbenchChatResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class LearningRequestCoordinatorTest {

    private final LearningSessionEventRepository repository = Mockito.mock(LearningSessionEventRepository.class);
    private final LearningRequestCoordinator coordinator = new LearningRequestCoordinator(repository, new ObjectMapper());

    @Test
    void replaysSucceededResponseForTheSameRequestHash() throws Exception {
        LearningSessionEventEntity event = event("MESSAGE", "hash-1");
        event.succeed(new ObjectMapper().writeValueAsString(
                LearningAssistantResponse.chat("session-1",
                        new WorkbenchChatResponse("message-1", "已恢复", List.of(), List.of()))));
        when(repository.findBySessionIdAndEventTypeAndClientRequestId("session-1", "MESSAGE", "request-1"))
                .thenReturn(Optional.of(event));

        LearningAssistantResponse response = coordinator.existing("session-1", "request-1", "MESSAGE", "hash-1");

        assertThat(response.answer()).isEqualTo("已恢复");
    }

    @Test
    void rejectsReusingClientRequestIdForDifferentContent() {
        LearningSessionEventEntity event = event("MESSAGE", "hash-1");
        when(repository.findBySessionIdAndEventTypeAndClientRequestId("session-1", "MESSAGE", "request-1"))
                .thenReturn(Optional.of(event));

        assertThatThrownBy(() -> coordinator.existing("session-1", "request-1", "MESSAGE", "hash-2"))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception ->
                        assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void rejectsReplayWhileOriginalRequestIsProcessing() {
        LearningSessionEventEntity event = event("MESSAGE", "hash-1");
        when(repository.findBySessionIdAndEventTypeAndClientRequestId("session-1", "MESSAGE", "request-1"))
                .thenReturn(Optional.of(event));

        assertThatThrownBy(() -> coordinator.existing("session-1", "request-1", "MESSAGE", "hash-1"))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception ->
                        assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void claimMarksExpiredProcessingLeaseAndCreatesPlaceholder() {
        LearningSessionEntity session = new LearningSessionEntity(
                "session-1", 7L, "workspace-1", "conversation-1", "标题", null, LearningMode.AUTO, "BEGINNER");
        when(repository.saveAndFlush(any(LearningSessionEventEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        LearningSessionEventEntity event = coordinator.claim(session, "request-1", "MESSAGE", "hash-1");

        assertThat(event.getStatus()).isEqualTo("PROCESSING");
        assertThat(event.getRequestHash()).isEqualTo("hash-1");
        verify(repository).expireProcessing(eq("session-1"), eq("EXPIRED"), any(Instant.class));
    }

    @Test
    void keepsAbandonedEventForTraceability() {
        LearningSessionEventEntity event = event("MESSAGE", "hash-1");
        when(repository.findById("event-1")).thenReturn(Optional.of(event));
        when(repository.abandon("event-1", event.getGeneration(), "ABANDONED")).thenReturn(1);

        coordinator.abandon("event-1", event.getGeneration());

        verify(repository).abandon("event-1", event.getGeneration(), "ABANDONED");
    }

    private LearningSessionEventEntity event(String type, String hash) {
        return new LearningSessionEventEntity("event-1", "session-1", 7L, "workspace-1", "request-1", type, hash);
    }
}
