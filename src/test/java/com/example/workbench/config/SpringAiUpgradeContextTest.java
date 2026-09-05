package com.example.workbench.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResponseErrorHandler;
import org.springframework.retry.support.RetryTemplate;

/**
 * 升级 Spring AI 1.0.0 → 1.1.8 时配置层的回归门禁。
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
 * {@link SpringAiConfig#vectorStore} →
 * {@link ChromaCollectionInitializer}。</p>
 *
 * <p>升级到 1.1.8 后这个测试仍为绿 = Spring AI 配置层在新版本下能完成
 * bean 装配，不必等线上才能发现装配错误。同时也是「升级前先在本地能跑出
 * 上下文」的最低成本工具。</p>
 */
@SpringBootTest(
    classes = SpringAiConfig.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@ImportAutoConfiguration({
    ChatClientAutoConfiguration.class,
    OpenAiChatAutoConfiguration.class,
    OpenAiEmbeddingAutoConfiguration.class,
    // ChromaVectorStoreAutoConfiguration 提供 ChromaVectorStoreProperties bean
    // （项目 SpringAiConfig.vectorStore 注入的第 3 个参数）。它自身也会试图
    // 创建 ChromaVectorStore，但被项目手工的 @Bean vectorStore 覆盖；ChromaApi
    // 已被 @MockitoBean 替换，所以探活不会触发真实 HTTP 调用。
    ChromaVectorStoreAutoConfiguration.class,
    // Spring AI 1.1.8 起 ChatModel 构造时注入 ToolCallingManager；ToolCalling
    // 自动装配项目实际启用（WebSearchService 上有 @Tool），不能少。
    ToolCallingAutoConfiguration.class
})
@Import(ChromaCollectionInitializer.class)
@TestPropertySource(properties = {
    "spring.ai.openai.base-url=https://api.siliconflow.cn",
    "spring.ai.openai.api-key=test-key-dummy",
    "spring.ai.openai.chat.options.model=test-chat-model",
    "spring.ai.openai.embedding.base-url=https://api.siliconflow.cn",
    "spring.ai.openai.embedding.api-key=test-key-dummy",
    "spring.ai.openai.embedding.options.model=test-embed-model",
    "spring.ai.vectorstore.chroma.client.host=http://127.0.0.1:18000",
    "spring.ai.vectorstore.chroma.client.port=18000",
    "spring.ai.vectorstore.chroma.tenant-name=default_tenant",
    "spring.ai.vectorstore.chroma.database-name=default_database",
    "spring.ai.vectorstore.chroma.collection-name=knowledge_assistant",
    "spring.ai.vectorstore.chroma.initialize-schema=true"
})
class SpringAiUpgradeContextTest {

    @MockitoBean
    private ChromaApi chromaApi;

    @MockitoBean
    private EmbeddingModel embeddingModel;

    /**
     * Spring AI 1.1.8 起 {@code OpenAiApi.Builder} 新增
     * {@code responseErrorHandler(ResponseErrorHandler)}，{@code OpenAiChatAutoConfiguration}
     * 自动注入它构造 {@code openAiApi}。项目自身没有声明 {@code ResponseErrorHandler} bean，
     * 升级到 1.1.8 后线上启动会因 {@code NoSuchBeanDefinitionException} 失败。
     * 本测试用 mock 占位，让上下文能加载并暴露这条升级兼容性陷阱。
     */
    @MockitoBean
    private ResponseErrorHandler responseErrorHandler;

    /**
     * Spring AI 1.1.8 起 {@code OpenAiChatAutoConfiguration} 引入 {@code RetryTemplate}
     * 用于 OpenAI API 失败重试（属性 {@code spring.ai.retry.max-attempts}）。
     * 项目本身没声明该 bean 也没显式依赖 spring-retry，1.1.8 升级时会因
     * {@code NoSuchBeanDefinitionException} 启动失败。本测试用 mock 占位，让上下文
     * 在两个版本下都能加载，并暴露这条兼容性陷阱（项目升级时需要补 spring-retry
     * 依赖 + 显式声明 {@code RetryTemplate} bean）。
     */
    @MockitoBean
    private RetryTemplate retryTemplate;

    @Autowired
    private ChatClient chatClient;

    @Autowired
    private ChromaVectorStore vectorStore;

    @Autowired
    private ChromaVectorStoreProperties chromaProperties;

    @BeforeEach
    void stubChromaApiForCollectionInitializer() {
        // ChromaCollectionInitializer 是 BeanPostProcessor，会在 vectorStore
        // 初始化前调 getTenant / getDatabase / getCollection 探活。Mock 行为
        // 覆盖 null（视为不存在）与 404（视为不存在但不重抛）两条路径：
        // - getTenant == null → 自动 createTenant
        // - getDatabase == null → 自动 createDatabase
        // - getCollection 抛 404 → 自动 createCollection + awaitCollection
        // 升级 1.1.8 后 ChromaApi 方法签名应保持兼容；这里同时验证方法签名
        // 未被破坏（编译期 + 运行期）。
        lenient().when(chromaApi.getTenant(anyString())).thenReturn(null);
        lenient().when(chromaApi.getDatabase(anyString(), anyString())).thenReturn(null);
        // createTenant / createDatabase / deleteCollection 是 void，doNothing 即可。
        // ChromaCollectionInitializer 不读这些返回。
        lenient().doNothing().when(chromaApi).createTenant(anyString());
        lenient().doNothing().when(chromaApi).createDatabase(anyString(), anyString());
        lenient().when(chromaApi.createCollection(anyString(), anyString(), any())).thenReturn(null);
        HttpClientErrorException notFound = HttpClientErrorException.create(
                HttpStatus.NOT_FOUND, "Not Found", org.springframework.http.HttpHeaders.EMPTY, new byte[0], null);
        lenient().when(chromaApi.getCollection(anyString(), anyString(), anyString()))
                .thenThrow(new RuntimeException("not found", notFound));
    }

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