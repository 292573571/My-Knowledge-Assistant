package com.example.workbench.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * 集中维护 RAG 业务指标，确保指标只使用有限枚举标签，不记录问题、用户或空间等敏感信息。
 */
@Component
public class RagMetrics {

    private final MeterRegistry registry;

    /**
     * 创建 RAG 指标记录器。
     *
     * @param registry Micrometer 指标注册表
     */
    public RagMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /**
     * 记录一个有返回值的 RAG 阶段耗时和结果。
     *
     * @param stage 阶段名称
     * @param action 阶段操作
     * @param <T> 返回值类型
     * @return 操作结果
     */
    public <T> T time(String stage, Supplier<T> action) {
        long startedAt = System.nanoTime();
        String outcome = "success";
        try {
            return action.get();
        } catch (RuntimeException exception) {
            outcome = "error";
            throw exception;
        } finally {
            Timer.builder("rag.stage.duration")
                    .description("RAG 关键阶段耗时")
                    .tag("stage", stage)
                    .tag("outcome", outcome)
                    .register(registry)
                    .record(System.nanoTime() - startedAt, TimeUnit.NANOSECONDS);
        }
    }

    /**
     * 记录检索返回数量。
     *
     * @param channel 检索通道
     * @param count 返回数量
     */
    public void recordRetrievalCount(String channel, int count) {
        registry.summary("rag.retrieval.result.count", "channel", channel).record(Math.max(0, count));
    }

    /**
     * 记录最终进入模型上下文的分块数量。
     *
     * @param count 上下文分块数量
     */
    public void recordContextCount(int count) {
        registry.summary("rag.context.source.count").record(Math.max(0, count));
    }

    /**
     * 记录模型调用结果、耗时和令牌数量。
     *
     * @param model 模型名称
     * @param role 主模型或备用模型
     * @param outcome 调用结果
     * @param durationNanos 调用耗时
     * @param promptTokens 输入令牌数
     * @param completionTokens 输出令牌数
     */
    public void recordModelCall(String model, String role, String outcome, long durationNanos,
                                Number promptTokens, Number completionTokens) {
        Timer.builder("rag.model.call.duration")
                .description("模型调用耗时")
                .tag("model", safeModel(model))
                .tag("role", role)
                .tag("outcome", outcome)
                .register(registry)
                .record(durationNanos, TimeUnit.NANOSECONDS);
        if (promptTokens != null) {
            Counter.builder("rag.model.tokens").tag("model", safeModel(model)).tag("type", "prompt")
                    .register(registry).increment(promptTokens.doubleValue());
        }
        if (completionTokens != null) {
            Counter.builder("rag.model.tokens").tag("model", safeModel(model)).tag("type", "completion")
                    .register(registry).increment(completionTokens.doubleValue());
        }
    }

    /**
     * 记录备用模型或本地回答兜底事件。
     *
     * @param strategy 兜底策略
     */
    public void recordFallback(String strategy) {
        registry.counter("rag.model.fallback.total", "strategy", strategy).increment();
    }

    /**
     * 记录文档任务最终或等待状态。
     *
     * @param type 任务类型
     * @param status 任务状态
     */
    public void recordDocumentTask(String type, String status) {
        registry.counter("document.task.transitions.total", "type", type, "status", status).increment();
    }

    private String safeModel(String model) {
        return model == null || model.isBlank() ? "unknown" : model;
    }
}
