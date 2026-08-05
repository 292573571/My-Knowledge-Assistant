package com.example.workbench.observability;

import org.springframework.ai.chroma.vectorstore.ChromaApi;
import org.springframework.ai.vectorstore.chroma.autoconfigure.ChromaVectorStoreProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * 通过读取集合元数据检查 Chroma 是否可用，不触发向量计算或写入。
 */
@Component("chroma")
public class ChromaHealthIndicator implements HealthIndicator {

    private final ObjectProvider<ChromaApi> apiProvider;
    private final ObjectProvider<ChromaVectorStoreProperties> propertiesProvider;

    /**
     * 创建 Chroma 健康检查器。
     *
     * @param apiProvider Chroma API 提供器
     * @param propertiesProvider Chroma 配置提供器
     */
    public ChromaHealthIndicator(ObjectProvider<ChromaApi> apiProvider,
                                 ObjectProvider<ChromaVectorStoreProperties> propertiesProvider) {
        this.apiProvider = apiProvider;
        this.propertiesProvider = propertiesProvider;
    }

    @Override
    public Health health() {
        ChromaApi api = apiProvider.getIfAvailable();
        ChromaVectorStoreProperties properties = propertiesProvider.getIfAvailable();
        if (api == null || properties == null) {
            return Health.up().withDetail("mode", "in-memory-fallback").build();
        }
        try {
            ChromaApi.Collection collection = api.getCollection(
                    properties.getTenantName(), properties.getDatabaseName(), properties.getCollectionName());
            return collection == null
                    ? Health.down().withDetail("reason", "collection-not-found").build()
                    : Health.up().withDetail("collectionAvailable", true).build();
        } catch (RuntimeException exception) {
            return Health.down().withException(exception).build();
        }
    }
}
