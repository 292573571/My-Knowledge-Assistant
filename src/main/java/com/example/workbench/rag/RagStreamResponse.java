package com.example.workbench.rag;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import reactor.core.publisher.Flux;

/**
 * 流式 RAG 响应：包含 token 流与 sources 列表。
 *
 * <p>字段均设为可变：礼貌拒绝兜底场景下
 * （{@code RagService.stream()} 的 LOCAL_KNOWLEDGE 路径流式订阅期间检测到模型礼貌拒绝，
 * 切到 web search 流式输出）需要：
 * <ul>
 *   <li>把 tokens 从本地流替换为 web search 流（让前端拿到正确的联网答案 token）</li>
 *   <li>把 sources 从本地 PDF 替换为 web 搜索来源（让前端展示正确的引用）</li>
 * </ul>
 *
 * <p>{@link #tokens()} / {@link #sources()} 始终返回当前最新值（订阅时拿快照），
 * 外部调用方拿值时语义不变。{@link #replaceTokens} / {@link #replaceSources} 仅在
 * RagService 包内使用。
 */
public final class RagStreamResponse {
    private final AtomicReference<Flux<String>> tokens;
    private final AtomicReference<List<RagSource>> sources;
    private final AtomicReference<RagAnswerRoute> route;
    private final AtomicReference<Function<String, List<RagSource>>> finalSourceResolver = new AtomicReference<>();

    public RagStreamResponse(Flux<String> tokens, List<RagSource> sources) {
        this(tokens, sources, sources == null || sources.isEmpty()
                ? RagAnswerRoute.NO_KNOWLEDGE : RagAnswerRoute.LOCAL_KNOWLEDGE);
    }

    public RagStreamResponse(Flux<String> tokens, List<RagSource> sources, RagAnswerRoute route) {
        this.tokens = new AtomicReference<>(tokens);
        this.sources = new AtomicReference<>(sources == null ? List.of() : List.copyOf(sources));
        this.route = new AtomicReference<>(route == null ? RagAnswerRoute.NO_KNOWLEDGE : route);
    }

    public Flux<String> tokens() {
        return tokens.get();
    }

    public List<RagSource> sources() {
        return sources.get();
    }

    public RagAnswerRoute route() {
        return route.get();
    }

    public List<RagSource> finalSources(String answer) {
        Function<String, List<RagSource>> resolver = finalSourceResolver.get();
        List<RagSource> resolved = resolver == null ? sources() : resolver.apply(answer == null ? "" : answer);
        replaceSources(resolved);
        return sources();
    }

    /**
     * 替换 tokens 流：用于礼貌拒绝兜底场景下从本地流切换为 web search 流。
     */
    void replaceTokens(Flux<String> newTokens) {
        tokens.set(newTokens);
    }

    /**
     * 替换 sources：用于礼貌拒绝兜底场景下从本地 sources 切换为 web 搜索来源。
     */
    void replaceSources(List<RagSource> newSources) {
        sources.set(newSources == null ? List.of() : List.copyOf(newSources));
    }

    void replaceRoute(RagAnswerRoute newRoute) {
        route.set(newRoute == null ? RagAnswerRoute.NO_KNOWLEDGE : newRoute);
    }

    void setFinalSourceResolver(Function<String, List<RagSource>> resolver) {
        finalSourceResolver.set(resolver);
    }

}
