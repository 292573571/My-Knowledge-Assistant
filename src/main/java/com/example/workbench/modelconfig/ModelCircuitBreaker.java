package com.example.workbench.modelconfig;

import com.example.workbench.modelconfig.CircuitBreakerStateStore.Snapshot;
import com.example.workbench.modelconfig.CircuitBreakerStateStore.State;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 模型熔断器。
 *
 * <p>对每个模型维护 closed/open/half-open 三态:连续失败达到阈值后熔断(open),
 * 冷却期内的请求直接拒绝(交由上层回退到备用模型);冷却结束后进入半开(half-open),
 * 下一次尝试成功则恢复(closed),失败则重新熔断。</p>
 *
 * <p>状态本身交由 {@link CircuitBreakerStateStore} 保存:进程内实现适用于单实例,
 * Redis 实现让多实例共享同一模型的健康判断,避免每个实例各自把超时试错一遍。</p>
 */
@Component
public class ModelCircuitBreaker {

    private static final Logger log = LoggerFactory.getLogger(ModelCircuitBreaker.class);

    private final int failureThreshold;
    private final long cooldownMs;
    private final CircuitBreakerStateStore store;
    /** per-model 本地锁:把同实例内的读-改-写串行化,减少竞态(跨实例的轻微竞态可接受)。 */
    private final Map<String, Object> locks = new ConcurrentHashMap<>();

    public ModelCircuitBreaker(
            @Value("${app.ai.circuit-breaker.failure-threshold:3}") int failureThreshold,
            @Value("${app.ai.circuit-breaker.cooldown-ms:30000}") long cooldownMs,
            CircuitBreakerStateStore store) {
        this.failureThreshold = Math.max(1, failureThreshold);
        this.cooldownMs = Math.max(1000, cooldownMs);
        this.store = store;
        log.info("模型熔断器已就绪 stateBackend={} failureThreshold={} cooldownMs={}",
                store.name(), this.failureThreshold, this.cooldownMs);
    }

    /** 该模型是否允许发起请求。open 态冷却期内返回 false,冷却结束后放行一次(half-open)。 */
    public boolean allowRequest(String model) {
        synchronized (lock(model)) {
            Snapshot snapshot = store.read(model);
            if (snapshot.state() != State.OPEN) {
                return true;
            }
            if (System.currentTimeMillis() - snapshot.openedAt() >= cooldownMs) {
                store.write(model, new Snapshot(State.HALF_OPEN, snapshot.consecutiveFailures(), snapshot.openedAt()));
                return true;
            }
            return false;
        }
    }

    public void recordSuccess(String model) {
        synchronized (lock(model)) {
            Snapshot snapshot = store.read(model);
            if (snapshot.state() != State.CLOSED) {
                log.info("Model circuit breaker recovered model={} state=closed", model);
            }
            store.write(model, Snapshot.closed());
        }
    }

    public void recordFailure(String model) {
        synchronized (lock(model)) {
            Snapshot snapshot = store.read(model);
            int failures = snapshot.consecutiveFailures() + 1;
            if (snapshot.state() == State.HALF_OPEN) {
                store.write(model, new Snapshot(State.OPEN, failures, System.currentTimeMillis()));
                log.warn("Model circuit breaker reopened model={} cooldownMs={}", model, cooldownMs);
                return;
            }
            if (failures >= failureThreshold) {
                store.write(model, new Snapshot(State.OPEN, failures, System.currentTimeMillis()));
                log.warn("Model circuit breaker opened model={} consecutiveFailures={} cooldownMs={}",
                        model, failures, cooldownMs);
                return;
            }
            store.write(model, new Snapshot(State.CLOSED, failures, 0L));
        }
    }

    private Object lock(String model) {
        return locks.computeIfAbsent(model, key -> new Object());
    }
}
