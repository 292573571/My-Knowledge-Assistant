package com.example.workbench.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chroma.vectorstore.ChromaApi;
import org.springframework.ai.vectorstore.chroma.autoconfigure.ChromaVectorStoreProperties;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.actuate.health.Status;

class ChromaHealthIndicatorTest {

    @Test
    void reportsInMemoryFallbackAsHealthyWhenChromaIsNotConfigured() {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        ChromaHealthIndicator indicator = new ChromaHealthIndicator(
                beans.getBeanProvider(ChromaApi.class), beans.getBeanProvider(ChromaVectorStoreProperties.class));

        assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
        assertThat(indicator.health().getDetails()).containsEntry("mode", "in-memory-fallback");
    }

    @Test
    void reportsChromaFailureAsDown() {
        ChromaApi api = mock(ChromaApi.class);
        ChromaVectorStoreProperties properties = new ChromaVectorStoreProperties();
        when(api.getCollection(properties.getTenantName(), properties.getDatabaseName(), properties.getCollectionName()))
                .thenThrow(new IllegalStateException("unavailable"));
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("api", api);
        beans.addBean("properties", properties);
        ChromaHealthIndicator indicator = new ChromaHealthIndicator(
                beans.getBeanProvider(ChromaApi.class), beans.getBeanProvider(ChromaVectorStoreProperties.class));

        assertThat(indicator.health().getStatus()).isEqualTo(Status.DOWN);
    }
}
