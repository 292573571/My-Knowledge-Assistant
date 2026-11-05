package com.example.workbench.config;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 给 Spring Boot 自动装配的 {@code RestClient.Builder}（Spring AI OpenAI 客户端底层）补超时。
 *
 * <p>Spring AI 1.0 自动装配的 OpenAI 客户端默认没有读取超时：评测出题（{@code EvalDraftService}）、
 * LLM 评审（{@code LlmJudgeEvaluator}）等阻塞式 {@code .call()} 在上游挂起时会无限阻塞，
 * 导致评测运行永远卡在「运行中」、草稿生成被 nginx 180s 网关超时打断。
 * 用户聊天 / Agent 走 {@code ModelClientFactory}（自带 10s 连接 + 60s 读取超时），不受本配置影响；
 * Chroma 客户端在 {@code ChromaHttpClientConfig} 单独配置，也不受影响。</p>
 */
@Configuration
public class SpringAiHttpTimeoutConfig {

    /**
     * 连接超时复用 {@code app.ai.connect-timeout-ms}（与 ModelClientFactory 一致）；
     * 读取超时默认 120s，覆盖评测出题 / LLM 评审这类长输出阻塞调用。
     */
    @Bean
    RestClientCustomizer springAiRestClientTimeoutCustomizer(
            @Value("${app.ai.connect-timeout-ms:10000}") long connectTimeoutMs,
            @Value("${app.ai.default-chat-read-timeout-ms:120000}") long readTimeoutMs) {
        return builder -> builder.requestFactory(ClientHttpRequestFactoryBuilder.detect().build(
                ClientHttpRequestFactorySettings.defaults()
                        .withConnectTimeout(Duration.ofMillis(connectTimeoutMs))
                        .withReadTimeout(Duration.ofMillis(readTimeoutMs))));
    }
}
