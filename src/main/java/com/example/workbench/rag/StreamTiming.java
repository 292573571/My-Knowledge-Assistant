package com.example.workbench.rag;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import reactor.core.publisher.Flux;

/**
 * 流式链路分段计时器，并负责给流挂纯观测钩子。
 *
 * <p>只用于性能排查，不参与任何业务判断，也不跨线程共享。从 {@code RagService} 外提（D-4 的一部分），
 * {@link #instrument} 保证「不修改元素序列、不改变订阅语义」，仅在结束/取消/异常时输出一条日志。</p>
 */
final class StreamTiming {

    private final String conversationId;
    private final long startedAt = System.nanoTime();
    private final List<String> stages = new ArrayList<>();
    private long lastMarkedAt = startedAt;

    StreamTiming(String conversationId) {
        this.conversationId = conversationId;
    }

    /**
     * 记录相对上一节点的耗时。
     *
     * @param stage 阶段名
     */
    void mark(String stage) {
        markSince(stage, lastMarkedAt);
    }

    /**
     * 记录相对任意时刻的耗时（用于首字延迟）。
     *
     * @param stage      阶段名
     * @param sinceNanos 起始时刻（{@link System#nanoTime()}）
     */
    void markSince(String stage, long sinceNanos) {
        long now = System.nanoTime();
        stages.add(stage + "=" + (now - sinceNanos) / 1_000_000L + "ms");
        lastMarkedAt = now;
    }

    long elapsedMs() {
        return (System.nanoTime() - startedAt) / 1_000_000L;
    }

    String conversationId() {
        return conversationId;
    }

    String describe() {
        return String.join(" ", stages);
    }

    /**
     * 给流式响应挂计时钩子，观测「订阅 → 首字 → 结束」三段时间。
     *
     * @param source   原始流
     * @param timing   计时器
     * @param path     链路标识（rag / webSearch / modelFallback）
     * @param warnMs   慢请求阈值，超过则日志升级为 WARN
     * @param log      调用方的 logger，保持日志分类不变
     * @return 挂好钩子的流；入参为 null 时返回 null
     */
    static Flux<String> instrument(Flux<String> source, StreamTiming timing, String path, long warnMs, Logger log) {
        if (source == null) {
            return null;
        }
        AtomicLong subscribedAt = new AtomicLong();
        AtomicBoolean firstChunk = new AtomicBoolean();
        AtomicInteger chunks = new AtomicInteger();
        return source
                .doOnSubscribe(subscription -> subscribedAt.set(System.nanoTime()))
                .doOnNext(chunk -> {
                    chunks.incrementAndGet();
                    if (firstChunk.compareAndSet(false, true)) {
                        timing.markSince("firstChunk", subscribedAt.get());
                    }
                })
                .doOnComplete(() -> logStreamTiming("finished", timing,
                        "path=" + path + " outcome=completed chunks=" + chunks.get(), warnMs, log))
                .doOnError(error -> logStreamTiming("finished", timing,
                        "path=" + path + " outcome=error:" + error.getClass().getSimpleName()
                                + " chunks=" + chunks.get(), warnMs, log))
                .doOnCancel(() -> logStreamTiming("finished", timing,
                        "path=" + path + " outcome=cancelled chunks=" + chunks.get(), warnMs, log));
    }

    /**
     * 输出流式链路的分段耗时。超过阈值打 WARN，否则打 INFO。
     *
     * @param phase   prepared（检索与提示词构建完成）/ finished（流结束）
     * @param timing  计时器
     * @param detail  附加信息
     * @param warnMs  慢请求阈值
     * @param log     logger
     */
    static void logStreamTiming(String phase, StreamTiming timing, String detail, long warnMs, Logger log) {
        long totalMs = timing.elapsedMs();
        String message = "RAG stream {} conversationId={} totalMs={} {} stages=[{}]";
        Object[] arguments = new Object[]{phase, timing.conversationId(), totalMs, detail, timing.describe()};
        if (totalMs >= warnMs) {
            log.warn(message, arguments);
        } else {
            log.info(message, arguments);
        }
    }
}
