package com.example.workbench.rag;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

/**
 * 「模型礼貌拒绝」判定口径：短答案 + 开头命中模板词才算拒绝。
 * 判定放宽会把正常回答误切到联网搜索，收紧则用户继续收到拒绝话术，两种都是线上事故。
 */
class StreamRefusalGuardTest {

    @Test
    @DisplayName("短拒绝话术判定为礼貌拒绝")
    void detectsShortRefusal() {
        assertThat(StreamRefusalGuard.isPoliteRefusal("抱歉，我无法回答这个问题。")).isTrue();
        assertThat(StreamRefusalGuard.isPoliteRefusal("抱歉，我无法提供相关信息，建议您补充资料。")).isTrue();
        assertThat(StreamRefusalGuard.isPoliteRefusal("知识库中没有相关内容。")).isTrue();
    }

    @Test
    @DisplayName("正常回答不判为拒绝")
    void keepsNormalAnswer() {
        assertThat(StreamRefusalGuard.isPoliteRefusal("RAG 的核心流程是文档导入、切片、向量化、检索与生成。")).isFalse();
        assertThat(StreamRefusalGuard.isPoliteRefusal("")).isFalse();
        assertThat(StreamRefusalGuard.isPoliteRefusal(null)).isFalse();
    }

    @Test
    @DisplayName("超过总长上限的长答案一律不判拒绝，避免正文谦词误伤")
    void ignoresLongAnswerEvenWithMarker() {
        String longAnswer = "抱歉让您久等了。" + "这是一段足够长的正常技术回答。".repeat(30);

        assertThat(longAnswer.length()).isGreaterThan(StreamRefusalGuard.MAX_LENGTH);
        assertThat(StreamRefusalGuard.isPoliteRefusal(longAnswer)).isFalse();
    }

    @Test
    @DisplayName("模板词只在前 100 字内生效")
    void markerOnlyCheckedInHead() {
        String answer = "本文介绍检索增强生成的流程。" + "细节描述。".repeat(25) + "抱歉，无法进一步展开。";

        assertThat(answer.length()).isLessThan(StreamRefusalGuard.MAX_LENGTH);
        assertThat(StreamRefusalGuard.isPoliteRefusal(answer)).isFalse();
    }

    @Test
    @DisplayName("正常流原样透传，不触发联网兜底")
    void passesThroughNormalStream() {
        Flux<String> base = Flux.just("RAG 的核心流程是", "文档导入与检索。");
        RagStreamResponse response = new RagStreamResponse(base, List.of(), RagAnswerRoute.LOCAL_KNOWLEDGE);
        AtomicInteger fallbackCalls = new AtomicInteger();

        StreamRefusalGuard.arm(base, response, "c-normal", () -> {
            fallbackCalls.incrementAndGet();
            return new RagStreamResponse(Flux.just("web"), List.of(), RagAnswerRoute.WEB_FALLBACK);
        }, log);

        assertThat(response.tokens().collectList().block())
                .containsExactly("RAG 的核心流程是", "文档导入与检索。");
        assertThat(fallbackCalls).hasValue(0);
        assertThat(response.route()).isEqualTo(RagAnswerRoute.LOCAL_KNOWLEDGE);
    }

    @Test
    @DisplayName("短拒绝话术在流结束时命中，整条流切换为联网结果")
    void switchesToWebOnShortRefusal() {
        Flux<String> base = Flux.just("抱歉，我无法回答", "这个问题。");
        RagStreamResponse response = new RagStreamResponse(base, List.of(), RagAnswerRoute.LOCAL_KNOWLEDGE);
        AtomicInteger fallbackCalls = new AtomicInteger();

        StreamRefusalGuard.arm(base, response, "c-short", () -> {
            fallbackCalls.incrementAndGet();
            return new RagStreamResponse(Flux.just("联网答案"), List.of(), RagAnswerRoute.WEB_FALLBACK);
        }, log);

        assertThat(response.tokens().collectList().block()).containsExactly("联网答案");
        assertThat(fallbackCalls).hasValue(1);
        assertThat(response.route()).isEqualTo(RagAnswerRoute.WEB_FALLBACK);
        assertThat(response.sources()).isEmpty();
    }

    @Test
    @DisplayName("探针达到 30 字即触发切换，不必等整条流结束")
    void switchesOnceProbeReachesThreshold() {
        String refusal = "抱歉，我无法回答这个问题，建议您补充相关资料后再重新提问，谢谢。";
        assertThat(refusal.length()).isGreaterThan(StreamRefusalGuard.PROBE_LENGTH);
        Flux<String> base = Flux.fromArray(refusal.split(""));
        RagStreamResponse response = new RagStreamResponse(base, List.of(), RagAnswerRoute.LOCAL_KNOWLEDGE);
        AtomicInteger fallbackCalls = new AtomicInteger();

        StreamRefusalGuard.arm(base, response, "c-probe", () -> {
            fallbackCalls.incrementAndGet();
            return new RagStreamResponse(Flux.just("联网答案"), List.of(), RagAnswerRoute.WEB_FALLBACK);
        }, log);

        assertThat(response.tokens().collectList().block()).containsExactly("联网答案");
        assertThat(fallbackCalls).hasValue(1);
    }

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(StreamRefusalGuardTest.class);
}
