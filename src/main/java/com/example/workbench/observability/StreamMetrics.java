package com.example.workbench.observability;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

@Component
public class StreamMetrics {

    private final AtomicInteger active = new AtomicInteger();

    public StreamMetrics(MeterRegistry registry) {
        registry.gauge("sse.active", active);
    }

    public void started() {
        active.incrementAndGet();
    }

    public void finished() {
        active.updateAndGet(value -> Math.max(0, value - 1));
    }
}
