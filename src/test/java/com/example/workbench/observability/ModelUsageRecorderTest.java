package com.example.workbench.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ModelUsageRecorderTest {

    private final ModelUsageEventRepository repository = mock(ModelUsageEventRepository.class);
    private final ModelUsageRecorder recorder = new ModelUsageRecorder(repository);

    @Test
    void persistsNormalizedUsageEvent() {
        recorder.record(7L, "conv-1", "gpt-x", "stream", 100, 50, null, 1200, true);

        ArgumentCaptor<ModelUsageEvent> captor = ArgumentCaptor.forClass(ModelUsageEvent.class);
        verify(repository, timeout(2000)).save(captor.capture());
        ModelUsageEvent event = captor.getValue();
        assertThat(event.getUserId()).isEqualTo(7L);
        assertThat(event.getConversationId()).isEqualTo("conv-1");
        assertThat(event.getModel()).isEqualTo("gpt-x");
        assertThat(event.getMode()).isEqualTo("stream");
        assertThat(event.getPromptTokens()).isEqualTo(100);
        assertThat(event.getCompletionTokens()).isEqualTo(50);
        assertThat(event.getTotalTokens()).isEqualTo(150);
        assertThat(event.getDurationMs()).isEqualTo(1200);
        assertThat(event.isSuccess()).isTrue();
        assertThat(event.getCreatedAt()).isNotNull();
    }

    @Test
    void clampsNegativeTokenValues() {
        recorder.record(1L, "c", "m", "sync", -5, null, -3, -10, false);

        ArgumentCaptor<ModelUsageEvent> captor = ArgumentCaptor.forClass(ModelUsageEvent.class);
        verify(repository, timeout(2000)).save(captor.capture());
        ModelUsageEvent event = captor.getValue();
        assertThat(event.getPromptTokens()).isZero();
        assertThat(event.getCompletionTokens()).isZero();
        assertThat(event.getTotalTokens()).isZero();
        assertThat(event.getDurationMs()).isZero();
        assertThat(event.isSuccess()).isFalse();
    }

    @Test
    void swallowsRepositoryFailure() {
        when(repository.save(any(ModelUsageEvent.class))).thenThrow(new IllegalStateException("db down"));

        assertThatCode(() -> recorder.record(1L, "c", "m", "sync", 1, 2, 3, 10, true))
                .doesNotThrowAnyException();
    }

    @Test
    void allowsNullUserIdForSystemCalls() {
        recorder.record(null, "default", "m", "sync", 1, 2, 3, 10, true);

        ArgumentCaptor<ModelUsageEvent> captor = ArgumentCaptor.forClass(ModelUsageEvent.class);
        verify(repository, timeout(2000)).save(captor.capture());
        assertThat(captor.getValue().getUserId()).isNull();
    }
}
