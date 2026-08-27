package com.example.workbench.learning;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class LearningOutboxEntityTest {
    @Test
    void rejectsRenewalAndCompletionFromAnOldLearningRecordGeneration() {
        LearningRecordOutboxEntity event = new LearningRecordOutboxEntity("record-1", 1L, "workspace-1", LocalDate.now());
        event.claim("worker-a", Instant.now().plusSeconds(60));
        long generation = event.generation();
        event.claim("worker-b", Instant.now().plusSeconds(60));

        assertThat(event.renewLease("worker-a", generation, Instant.now().plusSeconds(120))).isFalse();
        assertThat(event.ownsLease("worker-a", generation)).isFalse();
        assertThat(event.ownsLease("worker-b", event.generation())).isTrue();
    }

    @Test
    void rejectsRenewalFromAnOldFormalNoteGeneration() {
        FormalNoteOutboxEntity event = new FormalNoteOutboxEntity("note-1");
        event.claim("worker-a", Instant.now().plusSeconds(60));
        long generation = event.generation();
        event.claim("worker-b", Instant.now().plusSeconds(60));

        assertThat(event.renewLease("worker-a", generation, Instant.now().plusSeconds(120))).isFalse();
        assertThat(event.ownsLease("worker-b", event.generation())).isTrue();
    }
}
