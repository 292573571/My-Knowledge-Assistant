package com.example.workbench.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.workbench.rag.ScopedVectorStore;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Status;

class VectorStoreHealthIndicatorTest {

    @Test
    void reportsInMemoryFallbackAsHealthyWhenNoPersistentStore() {
        ScopedVectorStore vectorStore = mock(ScopedVectorStore.class);
        when(vectorStore.providerName()).thenReturn("memory");
        when(vectorStore.persistentStoreConfigured()).thenReturn(false);
        VectorStoreHealthIndicator indicator = new VectorStoreHealthIndicator(vectorStore);

        assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
        assertThat(indicator.health().getDetails()).containsEntry("mode", "in-memory-fallback");
    }

    @Test
    void reportsUpWithProviderWhenPersistentStoreIsReachable() {
        ScopedVectorStore vectorStore = mock(ScopedVectorStore.class);
        when(vectorStore.providerName()).thenReturn("pgvector");
        when(vectorStore.persistentStoreConfigured()).thenReturn(true);
        when(vectorStore.persistentStoreReachable()).thenReturn(true);
        VectorStoreHealthIndicator indicator = new VectorStoreHealthIndicator(vectorStore);

        assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
        assertThat(indicator.health().getDetails()).containsEntry("provider", "pgvector");
    }

    @Test
    void reportsDownWhenPersistentStoreIsUnreachable() {
        ScopedVectorStore vectorStore = mock(ScopedVectorStore.class);
        when(vectorStore.providerName()).thenReturn("chroma");
        when(vectorStore.persistentStoreConfigured()).thenReturn(true);
        when(vectorStore.persistentStoreReachable()).thenReturn(false);
        VectorStoreHealthIndicator indicator = new VectorStoreHealthIndicator(vectorStore);

        assertThat(indicator.health().getStatus()).isEqualTo(Status.DOWN);
        assertThat(indicator.health().getDetails()).containsEntry("provider", "chroma");
    }

    @Test
    void reportsDownWhenReachabilityProbeThrows() {
        ScopedVectorStore vectorStore = mock(ScopedVectorStore.class);
        when(vectorStore.providerName()).thenReturn("pgvector");
        when(vectorStore.persistentStoreConfigured()).thenReturn(true);
        when(vectorStore.persistentStoreReachable()).thenThrow(new IllegalStateException("db down"));
        VectorStoreHealthIndicator indicator = new VectorStoreHealthIndicator(vectorStore);

        assertThat(indicator.health().getStatus()).isEqualTo(Status.DOWN);
    }
}
