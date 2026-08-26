package com.example.workbench.streaming;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 流式缓冲相关配置。
 *
 * <p>{@code app.ai.stream.buffer-backend} 取值:</p>
 * <ul>
 *   <li>{@code auto}(默认)— Redis 可用则用 Redis,不可用自动降级为进程内缓冲并打 WARN</li>
 *   <li>{@code redis} — 强制使用 Redis;不可用时仍然降级,但日志升级为 ERROR 以便告警</li>
 *   <li>{@code memory} — 强制进程内缓冲,完全不接触 Redis(本地开发与单元测试默认路径)</li>
 * </ul>
 */
@Component
public class StreamBufferProperties {

    private final String backendMode;
    private final long ttlSeconds;

    public StreamBufferProperties(
            @Value("${app.ai.stream.buffer-backend:auto}") String backendMode,
            @Value("${app.ai.stream.buffer-ttl-seconds:300}") long ttlSeconds) {
        this.backendMode = backendMode == null ? "auto" : backendMode.trim();
        // 少于 30 秒的缓冲期没有续传意义(网络抖动恢复通常需要几秒到十几秒)。
        this.ttlSeconds = Math.max(30, ttlSeconds);
    }

    public String backendMode() {
        return backendMode;
    }

    public long ttlSeconds() {
        return ttlSeconds;
    }

    public boolean forceMemory() {
        return "memory".equalsIgnoreCase(backendMode);
    }

    public boolean requireRedis() {
        return "redis".equalsIgnoreCase(backendMode);
    }
}
