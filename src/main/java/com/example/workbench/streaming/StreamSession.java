package com.example.workbench.streaming;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * 一次流式回答的服务器端缓冲与订阅中心。
 *
 * <p>设计要点：生成任务(owner)与推送(emitter)解耦。生成任务只负责调用 {@link #append(String, Object)}
 * 把每个事件按顺序写入本会话；任意数量的订阅者(emitter)通过 {@link #subscribe(long, Consumer, Consumer)}
 * 接入,既能重放历史片段(断点续传),也能实时接收后续片段。</p>
 *
 * <p>这样即使客户端中途断线,生成任务仍会继续把剩余 token 写入缓冲;客户端重连时携带
 * {@code Last-Event-ID} 即可从断点接回,用户无感。</p>
 */
public class StreamSession {

    public enum Status {
        RUNNING,
        DONE,
        FAILED
    }

    private final String streamId;
    private final List<StreamChunk> chunks = new ArrayList<>();
    private final List<Subscriber> subscribers = new ArrayList<>();
    private final AtomicReference<Status> status = new AtomicReference<>(Status.RUNNING);
    private volatile StreamChunk terminal;
    private long seq = 0;
    /** append / snapshot / subscribe 必须互斥,保证重放与实时投递不重不漏。 */
    private final Object lock = new Object();

    public StreamSession(String streamId) {
        this.streamId = streamId;
    }

    public String streamId() {
        return streamId;
    }

    public Status status() {
        return status.get();
    }

    public StreamChunk terminalChunk() {
        return terminal;
    }

    /** 追加一个事件片段,通知所有在线订阅者,返回带序号的片段。 */
    public StreamChunk append(String event, Object data) {
        StreamChunk chunk;
        synchronized (lock) {
            chunk = new StreamChunk(++seq, event, data);
            chunks.add(chunk);
            for (Subscriber subscriber : subscribers) {
                subscriber.pending(chunk);
            }
        }
        return chunk;
    }

    /** 抓取序号大于 fromSeq 的历史片段,用于断线重连续传。 */
    public List<StreamChunk> snapshot(long fromSeq) {
        synchronized (lock) {
            List<StreamChunk> out = new ArrayList<>();
            for (StreamChunk chunk : chunks) {
                if (chunk.seq() > fromSeq) {
                    out.add(chunk);
                }
            }
            return out;
        }
    }

    /**
     * 接入一个订阅者。
     *
     * <p>调用方约定:先 {@link #snapshot(long)} 取历史,再以 {@code lastSnapshotSeq} 作为 fromSeq 订阅,
     * 最后重放 snapshot 中 {@code seq > fromSeq} 的片段。二者配合保证每个片段恰好投递一次。</p>
     *
     * <p>若接入时会话已终态,立即通过 {@code onTerminal} 回调把终态片段交给订阅者。</p>
     *
     * @return 取消订阅的句柄(幂等,可重复调用)
     */
    public Runnable subscribe(long fromSeq, Consumer<StreamChunk> onChunk, Consumer<StreamChunk> onTerminal) {
        Subscriber subscriber;
        synchronized (lock) {
            if (status.get() != Status.RUNNING) {
                StreamChunk terminalChunk = terminal;
                if (terminalChunk != null) {
                    onTerminal.accept(terminalChunk);
                }
                return () -> {
                };
            }
            subscriber = new Subscriber(fromSeq, onChunk, onTerminal);
            subscribers.add(subscriber);
        }
        return () -> {
            synchronized (lock) {
                subscribers.remove(subscriber);
            }
        };
    }

    public void markDone(StreamChunk terminalChunk) {
        transition(Status.DONE, terminalChunk);
    }

    public void markFailed(StreamChunk terminalChunk) {
        transition(Status.FAILED, terminalChunk);
    }

    private void transition(Status next, StreamChunk terminalChunk) {
        synchronized (lock) {
            if (status.compareAndSet(Status.RUNNING, next)) {
                this.terminal = terminalChunk;
                for (Subscriber subscriber : subscribers) {
                    subscriber.onTerminal(terminalChunk);
                }
                subscribers.clear();
            }
        }
    }

    private static final class Subscriber {

        private final long fromSeq;
        private final Consumer<StreamChunk> onChunk;
        private final Consumer<StreamChunk> onTerminal;

        private Subscriber(long fromSeq, Consumer<StreamChunk> onChunk, Consumer<StreamChunk> onTerminal) {
            this.fromSeq = fromSeq;
            this.onChunk = onChunk;
            this.onTerminal = onTerminal;
        }

        void pending(StreamChunk chunk) {
            if (chunk.seq() > fromSeq) {
                onChunk.accept(chunk);
            }
        }

        void onTerminal(StreamChunk terminalChunk) {
            onTerminal.accept(terminalChunk);
        }
    }
}
