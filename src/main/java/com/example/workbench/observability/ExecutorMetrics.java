package com.example.workbench.observability;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

@Component
public class ExecutorMetrics {

    public ExecutorMetrics(MeterRegistry registry,
                            @Qualifier("applicationTaskExecutor") ThreadPoolTaskExecutor application,
                            @Qualifier("aiTaskExecutor") ThreadPoolTaskExecutor ai,
                            @Qualifier("streamTaskExecutor") ThreadPoolTaskExecutor stream,
                            @Qualifier("ocrTaskExecutor") ThreadPoolTaskExecutor ocr) {
        register(registry, "application", application);
        register(registry, "ai", ai);
        register(registry, "stream", stream);
        register(registry, "ocr", ocr);
    }

    private void register(MeterRegistry registry, String name, ThreadPoolTaskExecutor executor) {
        ThreadPoolExecutor pool = executor.getThreadPoolExecutor();
        registry.gauge("executor.active", java.util.List.of(io.micrometer.core.instrument.Tag.of("pool", name)), pool,
                ThreadPoolExecutor::getActiveCount);
        registry.gauge("executor.queue.size", java.util.List.of(io.micrometer.core.instrument.Tag.of("pool", name)), pool,
                value -> value.getQueue().size());
        registry.gauge("executor.pool.size", java.util.List.of(io.micrometer.core.instrument.Tag.of("pool", name)), pool,
                ThreadPoolExecutor::getPoolSize);
    }
}
