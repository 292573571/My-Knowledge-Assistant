package com.example.workbench.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chroma.vectorstore.ChromaApi;
import org.springframework.ai.chroma.vectorstore.ChromaVectorStore;
import org.springframework.ai.embedding.BatchingStrategy;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.chroma.autoconfigure.ChromaVectorStoreProperties;
import org.springframework.test.util.ReflectionTestUtils;

class SpringAiConfigTest {

    @Test
    void passesConfiguredTenantAndDatabaseToChromaVectorStore() {
        ChromaVectorStoreProperties properties = mock(ChromaVectorStoreProperties.class);
        when(properties.getTenantName()).thenReturn("default_tenant");
        when(properties.getDatabaseName()).thenReturn("default_database");
        when(properties.getCollectionName()).thenReturn("knowledge_assistant");
        when(properties.isInitializeSchema()).thenReturn(true);

        ChromaVectorStore vectorStore = new SpringAiConfig().vectorStore(
                mock(EmbeddingModel.class),
                mock(ChromaApi.class),
                properties,
                mock(BatchingStrategy.class)
        );

        assertThat(ReflectionTestUtils.getField(vectorStore, "tenantName")).isEqualTo("default_tenant");
        assertThat(ReflectionTestUtils.getField(vectorStore, "databaseName")).isEqualTo("default_database");
        assertThat(ReflectionTestUtils.getField(vectorStore, "collectionName")).isEqualTo("knowledge_assistant");
        assertThat(ReflectionTestUtils.getField(vectorStore, "initializeSchema")).isEqualTo(true);
    }
}
