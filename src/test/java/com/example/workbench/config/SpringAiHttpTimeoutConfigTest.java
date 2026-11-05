package com.example.workbench.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.web.client.RestClient;

class SpringAiHttpTimeoutConfigTest {

    @Test
    void restClientCutsOffHangingServerByReadTimeout() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            // 接受连接但不回任何字节，模拟上游 LLM 挂起
            CountDownLatch accepted = new CountDownLatch(1);
            Thread acceptor = new Thread(() -> {
                try (Socket socket = server.accept()) {
                    accepted.countDown();
                    TimeUnit.SECONDS.sleep(30);
                } catch (Exception ignored) {
                    // 测试结束时连接被关闭，忽略
                }
            });
            acceptor.setDaemon(true);
            acceptor.start();

            RestClientCustomizer customizer = new SpringAiHttpTimeoutConfig()
                    .springAiRestClientTimeoutCustomizer(500, 400);
            RestClient.Builder builder = RestClient.builder();
            customizer.customize(builder);
            RestClient client = builder.build();

            long start = System.nanoTime();
            assertThatThrownBy(() -> client.get()
                    .uri("http://127.0.0.1:" + server.getLocalPort() + "/hang")
                    .retrieve()
                    .body(String.class))
                    .isInstanceOf(Exception.class);
            long elapsedMs = Duration.ofNanos(System.nanoTime() - start).toMillis();

            // 读取超时 400ms 生效：远小于挂起服务器 30s 的窗口，也远小于无超时的永久阻塞
            assertThat(accepted.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(elapsedMs).isLessThan(10_000);
        }
    }
}
