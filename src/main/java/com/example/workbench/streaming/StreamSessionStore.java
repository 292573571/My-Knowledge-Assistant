package com.example.workbench.streaming;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 流式会话的查找入口。
 *
 * <p>以 {@code streamId}(即请求的 {@code clientRequestId})为键定位 {@link StreamSession}。
 * 会话的片段数据存放在 {@link StreamBufferBackend} 中,本类只维护「本进程内的会话视图」缓存,
 * 并负责把远端(其他实例)的新片段通知转发给对应会话。</p>
 *
 * <p>因此在 Redis 后端下,即使会话是由另一个实例创建的,本实例也能通过 {@link #get(String, Long)}
 * 重建视图并续传 —— 这是跨实例断点续传的基础。</p>
 */
@Component
public class StreamSessionStore {

    private static final Logger log = LoggerFactory.getLogger(StreamSessionStore.class);

    private final StreamBufferBackend backend;
    private final ConcurrentHashMap<String, StreamSession> localViews = new ConcurrentHashMap<>();
    private final Map<String, Long> viewCreatedAt = new ConcurrentHashMap<>();
    /** 本进程内缓存的会话创建者,用于越权校验(即使命中本地缓存也必须匹配)。 */
    private final Map<String, Long> streamOwners = new ConcurrentHashMap<>();
    private final ScheduledExecutorService reaper = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "stream-session-reaper");
        thread.setDaemon(true);
        return thread;
    });
    private final long ttlSeconds;

    public StreamSessionStore(StreamBufferBackend backend, StreamBufferProperties properties) {
        this.backend = backend;
        this.ttlSeconds = properties.ttlSeconds();
        long period = Math.max(15, ttlSeconds / 2);
        reaper.scheduleAtFixedRate(this::reap, period, period, TimeUnit.SECONDS);
        backend.onRemoteAppend(this::onRemoteAppend);
        log.info("流式缓冲后端已就绪 backend={} ttlSeconds={}", backend.name(), ttlSeconds);
    }

    /**
     * 查找会话:优先用本进程视图,其次按缓冲后端中的状态重建视图(跨实例续传)。
     *
     * <p>越权校验:如果会话已绑定创建者 userId,则传入的 userId 必须与之匹配,
     * 否则返回 {@code null}(视为不存在),防止攻击者仅凭 streamId 窃听他人回答。
     * 缓存命中时同样校验,确保本进程内也不会泄露他人会话。</p>
     */
    public StreamSession get(String streamId, Long userId) {
        StreamSession cached = localViews.get(streamId);
        if (cached != null) {
            Long owner = streamOwners.get(streamId);
            if (owner != null && !owner.equals(userId)) {
                log.warn("流式会话越权访问被拒绝(缓存命中) streamId={} requester={} owner={}", streamId, userId, owner);
                return null;
            }
            return cached;
        }
        StreamBufferBackend.SessionState state = backend.readState(streamId);
        if (state == null) {
            return null;
        }
        if (state.userId() != null && !state.userId().equals(userId)) {
            log.warn("流式会话越权访问被拒绝 streamId={} requester={} owner={}", streamId, userId, state.userId());
            return null;
        }
        return localViews.computeIfAbsent(streamId, id -> {
            viewCreatedAt.put(id, System.nanoTime());
            streamOwners.put(id, userId);
            return new StreamSession(id, backend);
        });
    }

    public StreamSession create(String streamId, Long userId) {
        backend.createSession(streamId, userId);
        StreamSession session = new StreamSession(streamId, backend);
        localViews.put(streamId, session);
        viewCreatedAt.put(streamId, System.nanoTime());
        streamOwners.put(streamId, userId);
        return session;
    }

    public void remove(String streamId) {
        localViews.remove(streamId);
        viewCreatedAt.remove(streamId);
        streamOwners.remove(streamId);
        backend.remove(streamId);
    }

    /** 缓冲后端名称,用于诊断端点与启动日志。 */
    public String backendName() {
        return backend.name();
    }

    private void onRemoteAppend(String streamId) {
        StreamSession session = localViews.get(streamId);
        if (session != null) {
            session.drainRemote();
        }
    }

    /**
     * 清理本进程内的过期会话视图。
     *
     * <p>只清理视图,不动缓冲数据:Redis 后端靠键 TTL 自动回收;进程内后端的数据随视图一并释放。
     * RUNNING 中的会话永不清理,避免把正在生成的回答踢掉。</p>
     */
    private void reap() {
        long now = System.nanoTime();
        long ttlNanos = ttlSeconds * 1_000_000_000L;
        for (Map.Entry<String, Long> entry : viewCreatedAt.entrySet()) {
            if (now - entry.getValue() <= ttlNanos) {
                continue;
            }
            String streamId = entry.getKey();
            StreamBufferBackend.SessionState state = backend.readState(streamId);
            if (state == null || state.status() != StreamSession.Status.RUNNING) {
                localViews.remove(streamId);
                viewCreatedAt.remove(streamId);
                streamOwners.remove(streamId);
                if (state != null) {
                    backend.remove(streamId);
                }
            }
        }
    }

    @PreDestroy
    public void shutdown() {
        reaper.shutdownNow();
    }
}
