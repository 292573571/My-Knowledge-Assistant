package com.example.workbench.streaming;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 进程内流式缓冲后端。
 *
 * <p>零外部依赖,用于本地开发、单元测试,以及 Redis 不可达时的自动降级。
 * 局限:进程重启即丢失、无法跨实例续传 —— 这两点由 {@link RedisStreamBufferBackend} 解决。</p>
 */
public class MemoryStreamBufferBackend implements StreamBufferBackend {

    private final Map<String, SessionData> sessions = new ConcurrentHashMap<>();

    @Override
    public String name() {
        return "memory";
    }

    @Override
    public long nextSeq(String streamId) {
        return data(streamId).seq.incrementAndGet();
    }

    @Override
    public void appendChunk(String streamId, StreamChunk chunk) {
        SessionData data = data(streamId);
        synchronized (data.chunks) {
            data.chunks.add(chunk);
        }
    }

    @Override
    public List<StreamChunk> readChunks(String streamId, long fromSeq) {
        SessionData data = sessions.get(streamId);
        if (data == null) {
            return List.of();
        }
        synchronized (data.chunks) {
            List<StreamChunk> out = new ArrayList<>();
            for (StreamChunk chunk : data.chunks) {
                if (chunk.seq() > fromSeq) {
                    out.add(chunk);
                }
            }
            return out;
        }
    }

    @Override
    public void createSession(String streamId, Long userId) {
        sessions.computeIfAbsent(streamId, key -> new SessionData()).userId = userId;
    }

    @Override
    public void saveTerminal(String streamId, StreamSession.Status status, StreamChunk terminal) {
        SessionData data = data(streamId);
        data.status = status;
        data.terminalSeq = terminal == null ? 0L : terminal.seq();
    }

    @Override
    public SessionState readState(String streamId) {
        SessionData data = sessions.get(streamId);
        if (data == null) {
            return null;
        }
        return new SessionState(data.status, data.terminalSeq, data.userId);
    }

    @Override
    public void remove(String streamId) {
        sessions.remove(streamId);
    }

    private SessionData data(String streamId) {
        return sessions.computeIfAbsent(streamId, key -> new SessionData());
    }

    private static final class SessionData {
        private final AtomicLong seq = new AtomicLong(0);
        private final List<StreamChunk> chunks = new ArrayList<>();
        private volatile StreamSession.Status status = StreamSession.Status.RUNNING;
        private volatile long terminalSeq = 0L;
        private volatile Long userId = null;
    }
}
