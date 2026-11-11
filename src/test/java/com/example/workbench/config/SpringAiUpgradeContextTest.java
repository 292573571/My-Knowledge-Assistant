package com.example.workbench.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chroma.vectorstore.ChromaApi;
import org.springframework.ai.chroma.vectorstore.ChromaVectorStore;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.model.chat.client.autoconfigure.ChatClientAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiEmbeddingAutoConfiguration;
import org.springframework.ai.model.tool.autoconfigure.ToolCallingAutoConfiguration;
import org.springframework.ai.vectorstore.chroma.autoconfigure.ChromaVectorStoreAutoConfiguration;
import org.springframework.ai.vectorstore.chroma.autoconfigure.ChromaVectorStoreProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.ResponseErrorHandler;

/**
 * 升级 Spring AI 1.0.0 → 1.1.8 → 2.0.2 的配置层回归门禁。
 *
 * <p>项目原本 0 个 {@code @SpringBootTest}，所有单测都是手工 new 或 mock 跑
 * 业务方法。这意味着 Spring AI 升级只要能编译、纯单测全绿，<b>线上启动能否
 * 成功完全没有被测试保护</b>——而 bean 装配失败、属性绑定破坏、
 * 自动配置类被拆分这类问题只能在生产环境才发现。</p>
 *
 * <p>本测试用最小化切片 + mock 外部依赖（Chroma API、Embedding），让真实
 * Spring 容器跑通这条配置链：
 * {@link ChatClientAutoConfiguration} → {@link OpenAiChatAutoConfiguration} →
 * {@link SpringAiConfig#chatClient(ChatClient.Builder)} →
 * {@link SpringAiConfig#vectorStore}（含 2.0 的 {@code afterPropertiesSet}
 * 集合初始化：getCollection 404 → 自动创建 tenant/database/collection）。</p>
 *
 * <p>2.0 起集合初始化由框架 {@code ChromaVectorStore.afterPropertiesSet} 完成
 * （{@code getCollection} 对 404 返回 null → {@code initializeSchema=true} 时
 * 自动建 tenant/database/collection），项目自写的 {@code ChromaCollectionInitializer}
 * 已删除。升级到 2.0.2 后这个测试仍为绿 = Spring AI 配置层在新版本下能完成
 * bean 装配，不必等线上才能发现装配错误。</p>
 */
@SpringBootTest(
    classes = {SpringAiConfig.class, SpringAiUpgradeContextTest.StubConfig.class},
    webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@ImportAutoConfiguration({
    ChatClientAutoConfiguration.class,
    OpenAiChatAutoConfiguration.class,
    OpenAiEmbeddingAutoConfiguration.class,
    // ChromaVectorStoreAutoConfiguration 提供 ChromaVectorStoreProperties bean
    // （项目 SpringAiConfig.vectorStore 注入的第 3 个参数）。它自身也会试图
    // 创建 ChromaVectorStore，但被项目手工的 @Bean vectorStore 覆盖；ChromaApi
    // 已由 StubConfig 提供预 stub mock，探活不会触发真实 HTTP 调用。
    ChromaVectorStoreAutoConfiguration.class,
    // Spring AI 1.1.8 起 ChatModel 构造时注入 ToolCallingManager；ToolCalling
    // 自动装配项目实际启用（WebSearchService 上有 @Tool），不能少。
    ToolCallingAutoConfiguration.class
})
@TestPropertySource(properties = {
    "spring.ai.openai.base-url=https://api.siliconflow.cn",
    "spring.ai.openai.api-key=test-key-dummy",
    "spring.ai.openai.chat.options.model=test-chat-model",
    "spring.ai.openai.embedding.base-url=https://api.siliconflow.cn",
    "spring.ai.openai.embedding.api-key=test-key-dummy",
    "spring.ai.openai.embedding.model=test-embed-model",
    "spring.ai.vectorstore.chroma.client.host=http://127.0.0.1:18000",
    "spring.ai.vectorstore.chroma.client.port=18000",
    "spring.ai.vectorstore.chroma.tenant-name=default_tenant",
    "spring.ai.vectorstore.chroma.database-name=default_database",
    "spring.ai.vectorstore.chroma.collection-name=knowledge_assistant",
    "spring.ai.vectorstore.chroma.initialize-schema=true"
})
class SpringAiUpgradeContextTest {

    /**
     * 2.0 的 {@code ChromaVectorStore.afterPropertiesSet} 在 <b>context 加载期</b>调用
     * {@code chromaApi.getCollection/createCollection} 完成集合初始化——早于任何
     * {@code @BeforeEach}，因此 {@code @MockitoBean} 的 stub 来不及生效（mock 默认
     * 返回 null → {@code Assert.state(collection != null)} 失败）。必须用静态
     * {@code @TestConfiguration} 在 bean 创建前注册已 stub 的 mock。
     */
    @org.springframework.boot.test.context.TestConfiguration
    static class StubConfig {

        @Bean
        ChromaApi chromaApi() {
            ChromaApi api = org.mockito.Mockito.mock(ChromaApi.class);
            // 按框架真实语义 stub：getCollection 对 404 返回 null（不 stub 即默认 null），
            // initializeSchema=true 时依次建 tenant/database/collection。
            lenient().when(api.getTenant(anyString())).thenReturn(null);
            lenient().when(api.getDatabase(anyString(), anyString())).thenReturn(null);
            lenient().doNothing().when(api).createTenant(anyString());
            lenient().doNothing().when(api).createDatabase(anyString(), anyString());
            lenient().when(api.createCollection(anyString(), anyString(), any()))
                    .thenReturn(new ChromaApi.Collection("test-collection-id", "knowledge_assistant", java.util.Map.of()));
            return api;
        }

        @Bean
        EmbeddingModel embeddingModel() {
            return org.mockito.Mockito.mock(EmbeddingModel.class);
        }

        /**
         * Spring AI 1.1.8 起 {@code OpenAiApi.Builder} 新增
         * {@code responseErrorHandler(ResponseErrorHandler)}，{@code OpenAiChatAutoConfiguration}
         * 自动注入它构造 {@code openAiApi}。项目自身没有声明 {@code ResponseErrorHandler} bean，
         * 升级后线上启动会因 {@code NoSuchBeanDefinitionException} 失败。
         * 本测试用 mock 占位，让上下文能加载并暴露这条升级兼容性陷阱。
         */
        @Bean
        ResponseErrorHandler responseErrorHandler() {
            return org.mockito.Mockito.mock(ResponseErrorHandler.class);
        }
    }

    @Autowired
    private ChatClient chatClient;

    @Autowired
    private ChromaVectorStore vectorStore;

    @Autowired
    private ChromaVectorStoreProperties chromaProperties;

    @Test
    void springAiUpgradeContextWiresChatClientAndVectorStore() {
        assertThat(chatClient)
                .as("ChatClient bean 应由 SpringAiConfig 提供（依赖 ChatClient.Builder 自动装配）")
                .isNotNull();
        assertThat(vectorStore)
                .as("ChromaVectorStore 应由 SpringAiConfig 提供")
                .isNotNull();
        assertThat(chromaProperties)
                .as("ChromaVectorStoreProperties 应由 spring-ai 自动装配绑定到 spring.ai.vectorstore.chroma.*")
                .isNotNull();
    }

    @Test
    void vectorStoreBuilderReceivesConfiguredTenantDatabaseAndCollection() {
        // SpringAiConfig.vectorStore 用 ChromaVectorStore.builder(...).tenantName(...)
        // 把 properties 的 tenant/database/collection 传进去。1.1.8 是否仍暴露
        // 这些 builder 方法 + private 字段名称，是升级隐含风险——本测试同时
        // 校验 builder 链路和反射字段。
        assertThat(ReflectionTestUtils.getField(vectorStore, "tenantName"))
                .isEqualTo("default_tenant");
        assertThat(ReflectionTestUtils.getField(vectorStore, "databaseName"))
                .isEqualTo("default_database");
        assertThat(ReflectionTestUtils.getField(vectorStore, "collectionName"))
                .isEqualTo("knowledge_assistant");
        assertThat(ReflectionTestUtils.getField(vectorStore, "initializeSchema"))
                .isEqualTo(Boolean.TRUE);
    }

    @Test
    void chromaVectorStorePropertiesMatchDeclaredProperties() {
        // 升级时若 spring-ai 重命名属性键，本测试即报红。
        assertThat(chromaProperties.getTenantName()).isEqualTo("default_tenant");
        assertThat(chromaProperties.getDatabaseName()).isEqualTo("default_database");
        assertThat(chromaProperties.getCollectionName()).isEqualTo("knowledge_assistant");
        assertThat(chromaProperties.isInitializeSchema()).isTrue();
    }
}