package com.example.workbench.observability;

import com.example.workbench.rag.ScopedVectorStore;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * 向量存储健康检查：按活动 provider（chroma / pgvector / memory）实测连通性，不触发向量计算。
 * Bean 名（vectorStoreHealth）与 actuator readiness 健康组引用保持一致。
 * 注意不能叫 vectorStore —— 会与 SpringAiConfig.vectorStore 冲突（BeanDefinitionOverrideException）。
 */
@Component("vectorStoreHealth")
public class VectorStoreHealthIndicator implements HealthIndicator {

    private final ScopedVectorStore vectorStore;

    public VectorStoreHealthIndicator(ScopedVectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    @Override
    public Health health() {
        String provider = vectorStore.providerName();
        if (!vectorStore.persistentStoreConfigured()) {
            return Health.up()
                    .withDetail("provider", provider)
                    .withDetail("mode", "in-memory-fallback")
                    .build();
        }
        try {
            boolean reachable = vectorStore.persistentStoreReachable();
            Health.Builder builder = reachable ? Health.up() : Health.down();
            return builder.withDetail("provider", provider).build();
        } catch (RuntimeException exception) {
            return Health.down(exception)
                    .withDetail("provider", provider)
                    .build();
        }
    }
}
