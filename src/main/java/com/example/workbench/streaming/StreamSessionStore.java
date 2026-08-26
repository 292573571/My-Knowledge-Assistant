package com.example.workbench.streaming;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 流式会话缓冲的进程内存储。
 *
 * <p>以 {@code streamId}(即请求的 {@code clientRequestId})为键,保存 {@link StreamSession}。
 * 单实例部署下进程内存储足够;接口稳定,后续若需多实例可替换为 Redis 实现而不动上层逻辑。</p>
 *
 * <p>后台定时清理超过 TTL 的终态/过期会话,避免内存泄漏。</p>
 */
@Component
public class StreamSessionStore {

    private static final Logger log = LoggerFactory.getLogger(StreamSessionStore.class);

    private final ConcurrentHashMap<String, StreamSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, Long> createdAt = new ConcurrentHashMap<>();
    private final ScheduledExecutorService reaper = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "stream-session-reaper");
        thread.setDaemon(true);
        return thread;
    });
    private final long ttlSeconds;

    public StreamSessionStore(@Value("${app.ai.stream.buffer-ttl-seconds:300}") long ttlSeconds) {
        this.ttlSeconds = Math.max(30, ttlSeconds);
        long period = Math.max(15, ttlSeconds / 2);
        reaper.scheduleAtFixedRate(this::reap, period, period, TimeUnit.SECONDS);
    }

    public StreamSession get(String streamId) {
        return sessions.get(streamId);
    }

    public StreamSession create(String streamId) {
        StreamSession session = new StreamSession(streamId);
        sessions.put(streamId, session);
        createdAt.put(streamId, System.nanoTime());
        return session;
    }

    public void remove(String streamId) {
        sessions.remove(streamId);
        createdAt.remove(streamId);
    }

    private void reap() {
        long now = System.nanoTime();
        long ttlNanos = ttlSeconds * 1_000_000_000L;
        for (Map.Entry<String, Long> entry : createdAt.entrySet()) {
            if (now - entry.getValue() > ttlNanos) {
                StreamSession session = sessions.get(entry.getKey());
                if (session == null || session.status() != StreamSession.Status.RUNNING) {
                    sessions.remove(entry.getKey());
                    createdAt.remove(entry.getKey());
                }
            }
        }
    }

    @PreDestroy
    public void shutdown() {
        reaper.shutdownNow();
    }
}
