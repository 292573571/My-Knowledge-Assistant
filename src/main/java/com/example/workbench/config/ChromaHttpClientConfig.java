package com.example.workbench.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chroma.vectorstore.ChromaApi;
import org.springframework.ai.vectorstore.chroma.autoconfigure.ChromaApiProperties;
import org.springframework.ai.vectorstore.chroma.autoconfigure.ChromaConnectionDetails;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

/**
 * 给 Chroma 这一条出站链路装上连接/读超时。
 *
 * <p><b>为什么需要</b>：Spring AI 为 Chroma 自动装配的 {@link ChromaApi} 不带任何超时
 * （{@code spring.ai.vectorstore.chroma.*} 只有 host/port/tenant/database/collection）。
 * 当 Chroma 进程「卡死」而不是「退出」时，连接会建立但永不返回，调用方线程无限期阻塞 ——
 * 检索、入库、健康检查共用同一批 Web 线程，最终表现为整站假死，且没有任何失败出口。
 * 容器退出（连接被拒）反而不可怕，那会立刻报错。</p>
 *
 * <p><b>为什么在这里自定义</b>：自动配置的 {@code chromaApi} bean 带
 * {@code @ConditionalOnMissingBean}，本 bean 存在即让位。刻意<b>不复用</b>容器中的
 * {@code RestClient.Builder} —— 那个 builder 同时被模型调用（OpenAiApi）使用，
 * 读超时一旦外溢可能切断长时间生成的回答。这里独立构建，边界清晰：只影响 Chroma。</p>
 *
 * <p><b>代价</b>：将来若给容器 builder 增加全局定制（如 metrics 埋点、trace 透传），
 * Chroma 这条链路不会自动继承，需要同步到这里。</p>
 */
@Configuration
public class ChromaHttpClientConfig {

    private static final Logger log = LoggerFactory.getLogger(ChromaHttpClientConfig.class);

    @Bean
    ChromaApi chromaApi(ChromaApiProperties properties,
                        ChromaConnectionDetails connectionDetails,
                        ObjectMapper objectMapper,
                        @Value("${app.chroma.client.connect-timeout-ms:2000}") long connectTimeoutMs,
                        @Value("${app.chroma.client.read-timeout-ms:20000}") long readTimeoutMs) {
        ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.defaults()
                .withConnectTimeout(Duration.ofMillis(connectTimeoutMs))
                .withReadTimeout(Duration.ofMillis(readTimeoutMs));
        RestClient.Builder builder = RestClient.builder()
                .requestFactory(ClientHttpRequestFactoryBuilder.detect().build(settings));

        ChromaApi api = ChromaApi.builder()
                .baseUrl(String.format("%s:%s", connectionDetails.getHost(), connectionDetails.getPort()))
                .restClientBuilder(builder)
                .objectMapper(objectMapper)
                .build();
        if (StringUtils.hasText(connectionDetails.getKeyToken())) {
            api.withKeyToken(connectionDetails.getKeyToken());
        }
        if (StringUtils.hasText(properties.getUsername()) && StringUtils.hasText(properties.getPassword())) {
            api.withBasicAuthCredentials(properties.getUsername(), properties.getPassword());
        }
        log.info("Chroma 客户端超时已生效 connectTimeout={}ms readTimeout={}ms",
                connectTimeoutMs, readTimeoutMs);
        return api;
    }
}
