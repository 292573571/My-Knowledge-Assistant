package com.example.workbench.rag;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import org.slf4j.Logger;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

/**
 * 流式「礼貌拒绝」守卫：模型明明检索到了无关片段却回一句「抱歉，我无法回答」时，
 * 在前若干个 token 内识别并切换到联网搜索，避免用户拿到拒绝模板。
 *
 * <p>从 {@code RagService} 外提（D-4）。判定口径与探针长度原样保留：
 * 先累积 {@link #PROBE_LENGTH} 个字符做一次判定，未命中则把缓冲区原样下发后继续原流；
 * 流结束时若仍未达探针长度，再做一次完整答案判定（应对短答案也是拒绝的情况）。</p>
 */
final class StreamRefusalGuard {

    /** 答案总长上限：超过即视为正常长回答，不按「礼貌拒绝」处理。 */
    static final int MAX_LENGTH = 200;
    /** 只检查开头这一段，避免正文里偶然出现的谦词造成误判。 */
    static final int HEAD_LENGTH = 100;
    /** 流式探针长度：累积到约 30 个字符时做一次模板词命中判定。 */
    static final int PROBE_LENGTH = 30;

    /**
     * 模型礼貌拒绝模板词：触发 LOCAL_KNOWLEDGE → MODEL_ANSWER 兜底重路由到联网搜索。
     * 仅在答案前 100 字命中且答案总长 ≤ 200 字时算「礼貌拒绝」，避免误判合法回答中
     * 偶尔出现的「抱歉」等谦词。
     */
    private static final List<String> MARKERS = List.of(
            "抱歉", "对不起", "无法提供", "无法回答",
            "无能力", "无信息", "缺少相关", "知识库中没有",
            "超出", "建议您", "建议开启", "无足够",
            "无依据", "暂无", "没有足够", "无法基于"
    );

    private StreamRefusalGuard() {
    }

    /**
     * 判定生成答案是否属于「模型礼貌拒绝」：答案较短、开头包含拒绝模板词。
     *
     * @param answer 模型答案
     * @return true 表示应兜底重路由到联网搜索
     */
    static boolean isPoliteRefusal(String answer) {
        if (answer == null || answer.isBlank()) {
            return false;
        }
        String stripped = answer.strip();
        if (stripped.length() > MAX_LENGTH) {
            return false;
        }
        String head = stripped.substring(0, Math.min(stripped.length(), HEAD_LENGTH));
        return MARKERS.stream().anyMatch(head::contains);
    }

    /**
     * 给流挂上守卫：命中拒绝模板词时切断原流、下发联网搜索流，并同步替换响应的来源与路由。
     *
     * @param source         原始流
     * @param response       可变的流式响应，守卫会就地替换其 tokens / sources / route
     * @param conversationId 会话 id，仅用于日志
     * @param webFallback    联网搜索流的工厂，命中时才调用
     * @param log            调用方 logger，保持日志分类不变
     */
    static void arm(
            Flux<String> source,
            RagStreamResponse response,
            String conversationId,
            Supplier<RagStreamResponse> webFallback,
            Logger log
    ) {
        Flux<String> guardedFlux = Flux.create(sink -> {
            StringBuilder probe = new StringBuilder();
            boolean[] resolved = {false};
            boolean[] fellBack = {false};
            List<String> probeBuffer = new ArrayList<>();

            Disposable upstream = source.subscribe(
                    token -> {
                        if (resolved[0]) {
                            if (!fellBack[0]) {
                                sink.next(token);
                            }
                            return;
                        }
                        probeBuffer.add(token);
                        probe.append(token);
                        if (probe.length() < PROBE_LENGTH) {
                            return;
                        }
                        resolved[0] = true;
                        if (!isPoliteRefusal(probe.toString())) {
                            for (String t : probeBuffer) {
                                sink.next(t);
                            }
                            probeBuffer.clear();
                            return;
                        }
                        fellBack[0] = true;
                        log.warn(
                                "RAG stream model-polite-refusal detected, switching to web search conversationId={} probeHead={}",
                                conversationId,
                                probe.substring(0, Math.min(probe.length(), HEAD_LENGTH))
                        );
                        RagStreamResponse webResponse = webFallback.get();
                        // 替换 response 的 tokens 与 sources（让前端订阅时拿到正确的 web 数据）
                        response.replaceTokens(webResponse.tokens());
                        response.replaceSources(List.of());
                        response.replaceRoute(RagAnswerRoute.WEB_FALLBACK);
                        webResponse.tokens().subscribe(
                                sink::next,
                                sink::error,
                                () -> sink.complete()
                        );
                    },
                    err -> {
                        if (!fellBack[0]) {
                            sink.error(err);
                        }
                    },
                    () -> {
                        if (resolved[0]) {
                            if (!fellBack[0]) {
                                sink.complete();
                            }
                            return;
                        }
                        // 流完成但 probe 不足，做最终判定
                        resolved[0] = true;
                        if (!isPoliteRefusal(probe.toString())) {
                            for (String t : probeBuffer) {
                                sink.next(t);
                            }
                            sink.complete();
                            return;
                        }
                        fellBack[0] = true;
                        log.warn(
                                "RAG stream short model-polite-refusal detected, switching to web search conversationId={} probeLength={}",
                                conversationId,
                                probe.length()
                        );
                        RagStreamResponse webResponse = webFallback.get();
                        response.replaceTokens(webResponse.tokens());
                        response.replaceSources(List.of());
                        response.replaceRoute(RagAnswerRoute.WEB_FALLBACK);
                        webResponse.tokens().subscribe(
                                sink::next,
                                sink::error,
                                () -> sink.complete()
                        );
                    }
            );
            sink.onCancel(upstream::dispose);
        });
        response.replaceTokens(guardedFlux);
    }
}
