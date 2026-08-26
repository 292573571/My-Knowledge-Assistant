package com.example.workbench.modelconfig;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 进程内熔断状态存储(零依赖,单实例足够,也是 Redis 不可达时的降级路径)。
 */
public class MemoryCircuitBreakerStateStore implements CircuitBreakerStateStore {

    private final Map<String, Snapshot> states = new ConcurrentHashMap<>();

    @Override
    public String name() {
        return "memory";
    }

    @Override
    public Snapshot read(String model) {
        return states.getOrDefault(model, Snapshot.closed());
    }

    @Override
    public void write(String model, Snapshot snapshot) {
        states.put(model, snapshot);
    }
}
