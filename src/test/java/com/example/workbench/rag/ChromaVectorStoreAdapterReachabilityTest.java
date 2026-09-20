package com.example.workbench.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chroma.vectorstore.ChromaApi;
import org.springframework.ai.vectorstore.chroma.autoconfigure.ChromaVectorStoreProperties;
import org.springframework.beans.factory.ObjectProvider;

/**
 * 健康检查必须反映「现在能不能用」，而不是「Bean 有没有配出来」。
 * 这些用例锁住 chromaReachable() 的语义，防止退回成「只看 Bean 是否存在」。
 */
class ChromaVectorStoreAdapterReachabilityTest {

    private static final String TENANT = "default_tenant";
    private static final String DATABASE = "default_database";
    private static final String COLLECTION_NAME = "knowledge_assistant";
    private static final String COLLECTION_ID = "1a2b3c4d-1111-4222-8333-444455556666";

    private final ChromaApi api = mock(ChromaApi.class);
    private final ChromaVectorStoreProperties properties = mock(ChromaVectorStoreProperties.class);

    @Test
    void reportsReachableWhenProbeSucceeds() {
        givenCollectionExists();
        when(api.countEmbeddings(any(), any(), any())).thenReturn(586L);

        assertThat(adapter(api, properties).chromaReachable()).isTrue();
    }

    @Test
    void countsUsingResolvedCollectionIdNotName() {
        givenCollectionExists();
        when(api.countEmbeddings(any(), any(), any())).thenReturn(586L);

        adapter(api, properties).chromaReachable();

        // Chroma 的计数接口只收集合 UUID：传集合名会被拒为 "Collection ID is not a valid UUIDv4"。
        verify(api).countEmbeddings(TENANT, DATABASE, COLLECTION_ID);
        verify(api, times(1)).getCollection(TENANT, DATABASE, COLLECTION_NAME);
    }

    @Test
    void reportsUnreachableWhenProbeFails() {
        givenCollectionExists();
        when(api.countEmbeddings(any(), any(), any())).thenThrow(new RuntimeException("Connection refused"));

        assertThat(adapter(api, properties).chromaReachable()).isFalse();
    }

    @Test
    void reportsUnreachableWhenCollectionCannotBeResolved() {
        when(api.getCollection(any(), any(), any()))
                .thenThrow(new IllegalStateException("400 Bad Request: Collection ID is not a valid UUIDv4"));

        assertThat(adapter(api, properties).chromaReachable()).isFalse();
        verify(api, org.mockito.Mockito.never()).countEmbeddings(any(), any(), any());
    }

    @Test
    void reportsUnreachableWhenCollectionMissing() {
        when(api.getCollection(any(), any(), any())).thenReturn(null);

        assertThat(adapter(api, properties).chromaReachable()).isFalse();
    }

    @Test
    void reportsUnreachableWhenApiBeanMissing() {
        assertThat(adapter(null, properties).chromaReachable()).isFalse();
        assertThat(adapter(api, null).chromaReachable()).isFalse();
    }

    @Test
    void cachesProbeResultWithinWindow() {
        givenCollectionExists();
        when(api.countEmbeddings(any(), any(), any())).thenReturn(1L);
        ChromaVectorStoreAdapter adapter = adapter(api, properties);

        adapter.chromaReachable();
        adapter.chromaReachable();
        adapter.chromaReachable();

        // 健康检查可能被轮询；缓存窗口内不应每次都打网络（Chroma 故障时探测要等超时，代价更高）。
        verify(api, times(1)).countEmbeddings(any(), any(), any());
    }

    private void givenCollectionExists() {
        when(properties.getTenantName()).thenReturn(TENANT);
        when(properties.getDatabaseName()).thenReturn(DATABASE);
        when(properties.getCollectionName()).thenReturn(COLLECTION_NAME);
        when(api.getCollection(TENANT, DATABASE, COLLECTION_NAME))
                .thenReturn(new ChromaApi.Collection(COLLECTION_ID, COLLECTION_NAME, Map.of()));
    }

    private ChromaVectorStoreAdapter adapter(ChromaApi chromaApi, ChromaVectorStoreProperties chromaProperties) {
        return new ChromaVectorStoreAdapter(
                provider(null),
                new InMemoryVectorStore(),
                provider(chromaApi),
                provider(chromaProperties),
                Optional.empty());
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }
}
