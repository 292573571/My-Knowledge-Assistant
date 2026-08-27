package com.example.workbench.config;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class RateLimiter {
    private final ObjectProvider<StringRedisTemplate> redisTemplate;
    private final Map<String, Counter> localCounters = new ConcurrentHashMap<>();

    public RateLimiter(ObjectProvider<StringRedisTemplate> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public boolean tryAcquire(String bucket, String subject, int limit, Duration window) {
        int safeLimit = Math.max(1, limit);
        long windowMillis = Math.max(1_000L, window.toMillis());
        long windowNumber = System.currentTimeMillis() / windowMillis;
        String normalizedSubject = subject == null || subject.isBlank() ? "unknown" : subject.strip();
        String key = "rate-limit:" + bucket + ":" + normalizedSubject + ":" + windowNumber;
        StringRedisTemplate template = redisTemplate.getIfAvailable();
        if (template != null) {
            try {
                Long count = template.opsForValue().increment(key);
                if (count != null && count == 1L) {
                    template.expire(key, Duration.ofMillis(windowMillis + 1_000L));
                }
                return count != null && count <= safeLimit;
            } catch (RuntimeException ignored) {
            }
        }
        return tryAcquireLocally(key, safeLimit, windowMillis);
    }

    private boolean tryAcquireLocally(String key, int limit, long windowMillis) {
        long now = System.currentTimeMillis();
        Counter counter = localCounters.compute(key, (ignored, current) -> {
            if (current == null || now - current.startedAt() >= windowMillis) {
                return new Counter(now, new AtomicInteger(1));
            }
            current.count().incrementAndGet();
            return current;
        });
        if (localCounters.size() > 10_000) {
            localCounters.entrySet().removeIf(entry -> now - entry.getValue().startedAt() >= windowMillis);
        }
        return counter.count().get() <= limit;
    }

    private record Counter(long startedAt, AtomicInteger count) {
    }
}
