package com.example.workbench.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chroma.vectorstore.ChromaApi;
import org.springframework.ai.chroma.vectorstore.ChromaVectorStore;
import org.springframework.ai.embedding.BatchingStrategy;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.chroma.autoconfigure.ChromaVectorStoreProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SpringAiConfig {

    @Bean
    public ChatClient chatClient(ChatClient.Builder builder) {
        return builder.defaultSystem(AssistantPrompts.SYSTEM_PROMPT).build();
    }

    /**
     * 创建使用配置中 tenant 和 database 的 Chroma 向量存储。
     *
     * <p>Spring AI 1.0.0 的自动配置遗漏了这两个 builder 参数，会错误地回退到
     * {@code SpringAiTenant/SpringAiDatabase}，因此在此显式构建。</p>
     *
     * @param embeddingModel 向量化模型
     * @param chromaApi Chroma HTTP API
     * @param properties Chroma 向量存储配置
     * @param batchingStrategy embedding 批处理策略
     * @return 配置完整的 Chroma 向量存储
     */
    @Bean
    public ChromaVectorStore vectorStore(
            EmbeddingModel embeddingModel,
            ChromaApi chromaApi,
            ChromaVectorStoreProperties properties,
            BatchingStrategy batchingStrategy
    ) {
        return ChromaVectorStore.builder(chromaApi, embeddingModel)
                .tenantName(properties.getTenantName())
                .databaseName(properties.getDatabaseName())
                .collectionName(properties.getCollectionName())
                .initializeSchema(properties.isInitializeSchema())
                .batchingStrategy(batchingStrategy)
                .build();
    }
}
