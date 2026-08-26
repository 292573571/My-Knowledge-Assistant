package com.example.workbench.modelconfig;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 轻量级模型熔断器(无外部依赖,离线友好)。
 *
 * <p>对每个模型维护 closed/open/half-open 三态:连续失败达到阈值后熔断(open),
 * 冷却期内的请求直接拒绝(交由上层回退到备用模型);冷却结束后进入半开(half-open),
 * 下一次尝试成功则恢复(closed),失败则重新熔断。</p>
 *
 * <p>这是对企业级"熔断 + 模型回退链"的等价实现,避免引入 Resilience4j 带来的离线构建依赖。</p>
 */
@Component
public class ModelCircuitBreaker {

    private static final Logger log = LoggerFactory.getLogger(ModelCircuitBreaker.class);

    private final int failureThreshold;
    private final long cooldownMs;
    private final Map<String, Entry> states = new ConcurrentHashMap<>();

    public ModelCircuitBreaker(
            @Value("${app.ai.circuit-breaker.failure-threshold:3}") int failureThreshold,
            @Value("${app.ai.circuit-breaker.cooldown-ms:30000}") long cooldownMs) {
        this.failureThreshold = Math.max(1, failureThreshold);
        this.cooldownMs = Math.max(1000, cooldownMs);
    }

    /** 该模型是否允许发起请求。open 态冷却期内返回 false,冷却结束后放行一次(half-open)。 */
    public boolean allowRequest(String model) {
        Entry entry = states.computeIfAbsent(model, key -> new Entry());
        synchronized (entry) {
            if (entry.state == State.OPEN) {
                if (System.currentTimeMillis() - entry.openedAt >= cooldownMs) {
                    entry.state = State.HALF_OPEN;
                    entry.halfOpenAttempts = 0;
                    return true;
                }
                return false;
            }
            return true;
        }
    }

    public void recordSuccess(String model) {
        Entry entry = states.computeIfAbsent(model, key -> new Entry());
        synchronized (entry) {
            if (entry.state != State.CLOSED) {
                log.info("Model circuit breaker recovered model={} state=closed", model);
            }
            entry.consecutiveFailures = 0;
            entry.state = State.CLOSED;
        }
    }

    public void recordFailure(String model) {
        Entry entry = states.computeIfAbsent(model, key -> new Entry());
        synchronized (entry) {
            entry.consecutiveFailures++;
            if (entry.state == State.HALF_OPEN) {
                entry.state = State.OPEN;
                entry.openedAt = System.currentTimeMillis();
                log.warn("Model circuit breaker reopened model={} cooldownMs={}", model, cooldownMs);
            } else if (entry.consecutiveFailures >= failureThreshold) {
                entry.state = State.OPEN;
                entry.openedAt = System.currentTimeMillis();
                log.warn("Model circuit breaker opened model={} consecutiveFailures={} cooldownMs={}",
                        model, entry.consecutiveFailures, cooldownMs);
            }
        }
    }

    private enum State {
        CLOSED,
        OPEN,
        HALF_OPEN
    }

    private static final class Entry {
        private State state = State.CLOSED;
        private int consecutiveFailures = 0;
        private long openedAt = 0;
        @SuppressWarnings("unused")
        private int halfOpenAttempts = 0;
    }
}
