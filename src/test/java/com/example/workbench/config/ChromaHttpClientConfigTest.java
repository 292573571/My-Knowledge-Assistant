package com.example.workbench.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chroma.vectorstore.ChromaApi;
import org.springframework.ai.vectorstore.chroma.autoconfigure.ChromaApiProperties;
import org.springframework.ai.vectorstore.chroma.autoconfigure.ChromaConnectionDetails;

/**
 * 超时配置必须"真的生效"，而不是"写了就算"。
 * 这里用一个故意挂住的本地 HTTP 服务模拟卡死的 Chroma：
 * 修复前该调用会一直等下去（无限占用调用线程），修复后必须在读超时到达时抛错。
 */
class ChromaHttpClientConfigTest {

    @Test
    void slowChromaFailsAtReadTimeoutInsteadOfHangingForever() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try {
                Thread.sleep(2_000L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            exchange.sendResponseHeaders(200, 0);
            exchange.close();
        });
        server.start();
        try {
            ChromaApi api = api(server.getAddress().getPort(), 200L, 300L);

            long startedAt = System.nanoTime();
            assertThatThrownBy(() -> api.countEmbeddings("default_tenant", "default_database", "knowledge_assistant"))
                    .isInstanceOf(RuntimeException.class);
            Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);

            assertThat(elapsed).isLessThan(Duration.ofSeconds(1));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void buildsApiForReachableChroma() {
        assertThat(api(8000, 2_000L, 20_000L)).isNotNull();
    }

    private ChromaApi api(int port, long connectTimeoutMs, long readTimeoutMs) {
        ChromaConnectionDetails details = mock(ChromaConnectionDetails.class);
        when(details.getHost()).thenReturn("http://127.0.0.1");
        when(details.getPort()).thenReturn(port);
        return new ChromaHttpClientConfig()
                .chromaApi(new ChromaApiProperties(), details, new ObjectMapper(), connectTimeoutMs, readTimeoutMs);
    }
}
