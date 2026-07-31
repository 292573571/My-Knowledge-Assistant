package com.example.workbench.config;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.ConnectException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chroma.vectorstore.ChromaApi;
import org.springframework.ai.vectorstore.chroma.autoconfigure.ChromaVectorStoreProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;

class ChromaCollectionInitializerTest {

    @Test
    void createsAndConfirmsMissingCollectionBeforeVectorStoreInitialization() {
        ChromaApi chromaApi = mock(ChromaApi.class);
        ChromaVectorStoreProperties properties = properties();
        RuntimeException missing = new RuntimeException("Collection does not exist", notFound());
        when(chromaApi.getCollection("default_tenant", "default_database", "knowledge_assistant"))
                .thenThrow(missing)
                .thenReturn(mock(ChromaApi.Collection.class));

        initializer(chromaApi, properties).postProcessBeforeInitialization(new Object(), "vectorStore");

        verify(chromaApi).createCollection(
                org.mockito.ArgumentMatchers.eq("default_tenant"),
                org.mockito.ArgumentMatchers.eq("default_database"),
                org.mockito.ArgumentMatchers.any(ChromaApi.CreateCollectionRequest.class)
        );
        verify(chromaApi, times(2))
                .getCollection("default_tenant", "default_database", "knowledge_assistant");
    }

    @Test
    void doesNotTreatConnectionFailureAsMissingCollection() {
        ChromaApi chromaApi = mock(ChromaApi.class);
        ResourceAccessException unavailable = new ResourceAccessException("Connection refused", new ConnectException());
        when(chromaApi.getCollection("default_tenant", "default_database", "knowledge_assistant"))
                .thenThrow(unavailable);

        assertThatThrownBy(() -> initializer(chromaApi, properties())
                .postProcessBeforeInitialization(new Object(), "vectorStore"))
                .isSameAs(unavailable);
    }

    @SuppressWarnings("unchecked")
    private ChromaCollectionInitializer initializer(
            ChromaApi chromaApi,
            ChromaVectorStoreProperties properties
    ) {
        ObjectProvider<ChromaApi> apiProvider = mock(ObjectProvider.class);
        ObjectProvider<ChromaVectorStoreProperties> propertiesProvider = mock(ObjectProvider.class);
        when(apiProvider.getIfAvailable()).thenReturn(chromaApi);
        when(propertiesProvider.getIfAvailable()).thenReturn(properties);
        return new ChromaCollectionInitializer(apiProvider, propertiesProvider);
    }

    private ChromaVectorStoreProperties properties() {
        ChromaVectorStoreProperties properties = mock(ChromaVectorStoreProperties.class);
        when(properties.getTenantName()).thenReturn("default_tenant");
        when(properties.getDatabaseName()).thenReturn("default_database");
        when(properties.getCollectionName()).thenReturn("knowledge_assistant");
        return properties;
    }

    private HttpClientErrorException notFound() {
        return HttpClientErrorException.create(
                HttpStatus.NOT_FOUND,
                "Not Found",
                HttpHeaders.EMPTY,
                "{\"message\":\"Collection does not exist\"}".getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8
        );
    }
}
