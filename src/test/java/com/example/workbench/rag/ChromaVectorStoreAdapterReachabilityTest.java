package com.example.workbench.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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

    private final ChromaApi api = mock(ChromaApi.class);
    private final ChromaVectorStoreProperties properties = mock(ChromaVectorStoreProperties.class);

    @Test
    void reportsReachableWhenProbeSucceeds() {
        when(api.countEmbeddings(any(), any(), any())).thenReturn(586L);

        assertThat(adapter(api, properties).chromaReachable()).isTrue();
    }

    @Test
    void reportsUnreachableWhenProbeFails() {
        when(api.countEmbeddings(any(), any(), any())).thenThrow(new RuntimeException("Connection refused"));

        assertThat(adapter(api, properties).chromaReachable()).isFalse();
    }

    @Test
    void reportsUnreachableWhenApiBeanMissing() {
        assertThat(adapter(null, properties).chromaReachable()).isFalse();
        assertThat(adapter(api, null).chromaReachable()).isFalse();
    }

    @Test
    void cachesProbeResultWithinWindow() {
        when(api.countEmbeddings(any(), any(), any())).thenReturn(1L);
        ChromaVectorStoreAdapter adapter = adapter(api, properties);

        adapter.chromaReachable();
        adapter.chromaReachable();
        adapter.chromaReachable();

        // 健康检查可能被轮询；缓存窗口内不应每次都打网络（Chroma 故障时探测要等超时，代价更高）。
        verify(api, times(1)).countEmbeddings(any(), any(), any());
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
