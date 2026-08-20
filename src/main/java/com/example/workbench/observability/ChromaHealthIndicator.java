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
    private RagMetrics metrics;

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

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setMetrics(RagMetrics metrics) {
        this.metrics = metrics;
    }

    @Override
    public Health health() {
        long startedAt = System.nanoTime();
        ChromaApi api = apiProvider.getIfAvailable();
        ChromaVectorStoreProperties properties = propertiesProvider.getIfAvailable();
        if (api == null || properties == null) {
            if (metrics != null) metrics.recordChromaHealth("fallback", System.nanoTime() - startedAt);
            return Health.up().withDetail("mode", "in-memory-fallback").build();
        }
        String outcome = "up";
        try {
            ChromaApi.Collection collection = api.getCollection(
                    properties.getTenantName(), properties.getDatabaseName(), properties.getCollectionName());
            if (collection == null) {
                outcome = "down";
                return Health.down().withDetail("reason", "collection-not-found").build();
            }
            return Health.up().withDetail("collectionAvailable", true).build();
        } catch (RuntimeException exception) {
            outcome = "down";
            return Health.down().withException(exception).build();
        } finally {
            if (metrics != null) metrics.recordChromaHealth(outcome, System.nanoTime() - startedAt);
        }
    }
}
