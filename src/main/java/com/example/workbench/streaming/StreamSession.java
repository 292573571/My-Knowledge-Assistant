package com.example.workbench.streaming;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 一次流式回答的服务器端缓冲与订阅中心。
 *
 * <p>设计要点:生成任务(owner)与推送(emitter)解耦。生成任务只负责调用 {@link #append(String, Object)}
 * 把每个事件按顺序写入缓冲后端;任意数量的订阅者(emitter)通过
 * {@link #subscribe(long, Consumer, Consumer)} 接入,该方法在同一把锁内先重放历史片段(断点续传)
 * 再注册实时订阅,因此既不重复也不丢失、更不会乱序。</p>
 *
 * <p>这样即使客户端中途断线,生成任务仍会继续把剩余 token 写入缓冲;客户端重连时携带
 * {@code Last-Event-ID} 即可从断点接回,用户无感。</p>
 *
 * <p>片段的实际存储交由 {@link StreamBufferBackend}:进程内实现零依赖,Redis 实现支持
 * 重启不丢与跨实例续传。</p>
 */
public class StreamSession {

    public enum Status {
        RUNNING,
        DONE,
        FAILED
    }

    private final String streamId;
    private final StreamBufferBackend backend;
    private final List<Subscriber> subscribers = new ArrayList<>();
    /** append / subscribe / drainRemote 必须互斥,保证重放与实时投递不重不漏。 */
    private final Object lock = new Object();

    public StreamSession(String streamId, StreamBufferBackend backend) {
        this.streamId = streamId;
        this.backend = backend;
    }

    public String streamId() {
        return streamId;
    }

    public Status status() {
        StreamBufferBackend.SessionState state = backend.readState(streamId);
        return state == null ? Status.FAILED : state.status();
    }

    /** 读取 {@code fromSeq} 之后的所有已缓冲片段(不含 {@code fromSeq} 自身)。 */
    public List<StreamChunk> snapshot(long fromSeq) {
        return backend.readChunks(streamId, fromSeq);
    }

    /** 追加一个事件片段,通知所有在线订阅者,返回带序号的片段。 */
    public StreamChunk append(String event, Object data) {
        StreamChunk chunk = new StreamChunk(backend.nextSeq(streamId), event, data);
        backend.appendChunk(streamId, chunk);
        synchronized (lock) {
            for (Subscriber subscriber : subscribers) {
                subscriber.deliverIfNew(chunk);
            }
        }
        return chunk;
    }

    /**
     * 接入一个订阅者:先重放 {@code fromSeq} 之后的历史片段,再注册实时订阅。
     *
     * <p>两步在同一把锁内完成,因此重放期间产生的新片段只会在重放结束后按序投递,不会插队。</p>
     *
     * <p>若接入时会话已终态,重放完剩余片段后立即通过 {@code onTerminal} 通知订阅者收尾。
     * 注意终态片段(done / error)本身也是普通片段,已由 {@code onChunk} 投递,
     * {@code onTerminal} 只承担「可以关闭连接了」的信号。</p>
     *
     * @return 取消订阅的句柄(幂等,可重复调用)
     */
    public Runnable subscribe(long fromSeq, Consumer<StreamChunk> onChunk, Consumer<StreamChunk> onTerminal) {
        Subscriber subscriber;
        synchronized (lock) {
            long cursor = fromSeq;
            for (StreamChunk chunk : backend.readChunks(streamId, fromSeq)) {
                cursor = chunk.seq();
                onChunk.accept(chunk);
            }
            StreamBufferBackend.SessionState state = backend.readState(streamId);
            if (state == null || state.status() != Status.RUNNING) {
                onTerminal.accept(null);
                return () -> {
                };
            }
            subscriber = new Subscriber(cursor, onChunk, onTerminal);
            subscribers.add(subscriber);
        }
        return () -> {
            synchronized (lock) {
                subscribers.remove(subscriber);
            }
        };
    }

    /**
     * 从缓冲后端拉取增量片段并投递给本地订阅者。
     *
     * <p>由 Redis Pub/Sub 通知触发:当「生成」发生在另一个实例上时,本实例靠它把新 token 推给客户端。</p>
     */
    void drainRemote() {
        synchronized (lock) {
            if (subscribers.isEmpty()) {
                return;
            }
            long minCursor = Long.MAX_VALUE;
            for (Subscriber subscriber : subscribers) {
                minCursor = Math.min(minCursor, subscriber.cursor);
            }
            for (StreamChunk chunk : backend.readChunks(streamId, minCursor)) {
                for (Subscriber subscriber : subscribers) {
                    subscriber.deliverIfNew(chunk);
                }
            }
            StreamBufferBackend.SessionState state = backend.readState(streamId);
            if (state != null && state.status() != Status.RUNNING) {
                notifyTerminal();
            }
        }
    }

    public void markDone(StreamChunk terminalChunk) {
        transition(Status.DONE, terminalChunk);
    }

    public void markFailed(StreamChunk terminalChunk) {
        transition(Status.FAILED, terminalChunk);
    }

    private void transition(Status next, StreamChunk terminalChunk) {
        backend.saveTerminal(streamId, next, terminalChunk);
        synchronized (lock) {
            notifyTerminal();
        }
    }

    /** 调用方必须持有 {@link #lock}。 */
    private void notifyTerminal() {
        for (Subscriber subscriber : subscribers) {
            subscriber.onTerminal.accept(null);
        }
        subscribers.clear();
    }

    private static final class Subscriber {

        private final Consumer<StreamChunk> onChunk;
        private final Consumer<StreamChunk> onTerminal;
        /** 已投递给该订阅者的最大序号,用于幂等去重(本地投递与 Pub/Sub 回环可能重叠)。 */
        private long cursor;

        private Subscriber(long cursor, Consumer<StreamChunk> onChunk, Consumer<StreamChunk> onTerminal) {
            this.cursor = cursor;
            this.onChunk = onChunk;
            this.onTerminal = onTerminal;
        }

        void deliverIfNew(StreamChunk chunk) {
            if (chunk.seq() <= cursor) {
                return;
            }
            cursor = chunk.seq();
            onChunk.accept(chunk);
        }
    }
}
