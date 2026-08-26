package com.example.workbench.modelconfig;

import java.time.Duration;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 基于 Redis Hash 的熔断状态存储,让多实例共享同一模型的健康判断。
 *
 * <p>键为 {@code shihai:cb:{model}},字段 {@code state} / {@code failures} / {@code openedAt},
 * 带 TTL 自动回收(模型下线后不留脏状态)。</p>
 *
 * <p>任何 Redis 异常都退化为「按 CLOSED 处理」:熔断器的作用是减少无谓等待,
 * 它自身故障时应当放行请求而不是拦截业务。</p>
 */
public class RedisCircuitBreakerStateStore implements CircuitBreakerStateStore {

    private static final Logger log = LoggerFactory.getLogger(RedisCircuitBreakerStateStore.class);
    private static final String KEY_PREFIX = "shihai:cb:";

    private final StringRedisTemplate redis;
    private final Duration ttl;

    public RedisCircuitBreakerStateStore(StringRedisTemplate redis, Duration ttl) {
        this.redis = redis;
        this.ttl = ttl;
    }

    @Override
    public String name() {
        return "redis";
    }

    @Override
    public Snapshot read(String model) {
        try {
            Map<Object, Object> entries = redis.opsForHash().entries(key(model));
            if (entries == null || entries.isEmpty()) {
                return Snapshot.closed();
            }
            State state = parseState(entries.get("state"));
            int failures = (int) parseLong(entries.get("failures"));
            long openedAt = parseLong(entries.get("openedAt"));
            return new Snapshot(state, failures, openedAt);
        } catch (RuntimeException exception) {
            log.debug("熔断状态读取 Redis 失败,按 CLOSED 处理 model={} error={}", model, exception.getMessage());
            return Snapshot.closed();
        }
    }

    @Override
    public void write(String model, Snapshot snapshot) {
        try {
            redis.opsForHash().putAll(key(model), Map.of(
                    "state", snapshot.state().name(),
                    "failures", String.valueOf(snapshot.consecutiveFailures()),
                    "openedAt", String.valueOf(snapshot.openedAt())));
            redis.expire(key(model), ttl);
        } catch (RuntimeException exception) {
            log.debug("熔断状态写入 Redis 失败 model={} error={}", model, exception.getMessage());
        }
    }

    private static State parseState(Object raw) {
        if (raw == null) {
            return State.CLOSED;
        }
        try {
            return State.valueOf(raw.toString());
        } catch (IllegalArgumentException exception) {
            return State.CLOSED;
        }
    }

    private static long parseLong(Object raw) {
        if (raw == null) {
            return 0L;
        }
        try {
            return Long.parseLong(raw.toString());
        } catch (NumberFormatException exception) {
            return 0L;
        }
    }

    private static String key(String model) {
        return KEY_PREFIX + model;
    }
}
