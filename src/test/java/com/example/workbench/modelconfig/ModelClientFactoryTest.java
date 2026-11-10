package com.example.workbench.modelconfig;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

/**
 * clientFor 构建的客户端必须把 ModelSpec 里的模型名带进请求体。
 * 修复前 build() 不设 defaultOptions，教学 Agent 等走此工厂的调用发出的请求
 * 没有 model 字段，硅基流动返回 400（code=20015 parameter invalid）。
 */
class ModelClientFactoryTest {

    @Test
    void clientForSendsSpecModelInRequestBody() throws IOException {
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String response = """
                    {"id":"t","object":"chat.completion","created":1,"model":"deepseek-ai/DeepSeek-V4-Flash",
                     "choices":[{"index":0,"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}],
                     "usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}""";
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            ModelClientFactory factory = new ModelClientFactory(2_000, 10_000);
            ModelSpec spec = new ModelSpec("测试模型", "http://127.0.0.1:" + server.getAddress().getPort(),
                    "sk-test", "deepseek-ai/DeepSeek-V4-Flash", 0.2, null, null, 10_000L, null);
            ChatClient client = factory.clientFor(spec);

            String answer = client.prompt().user("你好").call().content();

            assertThat(answer).isEqualTo("ok");
            assertThat(requestBody.get()).contains("\"deepseek-ai/DeepSeek-V4-Flash\"");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void clientForAppliesTemperatureFromSpec() throws IOException {
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String response = """
                    {"id":"t","object":"chat.completion","created":1,"model":"deepseek-ai/DeepSeek-V4-Flash",
                     "choices":[{"index":0,"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}],
                     "usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}""";
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            ModelClientFactory factory = new ModelClientFactory(2_000, 10_000);
            ModelSpec spec = new ModelSpec("测试模型", "http://127.0.0.1:" + server.getAddress().getPort(),
                    "sk-test", "deepseek-ai/DeepSeek-V4-Flash", 0.35, null, null, 10_000L, null);
            ChatClient client = factory.clientFor(spec);

            client.prompt().user("你好").call().content();

            assertThat(requestBody.get()).contains("\"temperature\":0.35");
        } finally {
            server.stop(0);
        }
    }
}
