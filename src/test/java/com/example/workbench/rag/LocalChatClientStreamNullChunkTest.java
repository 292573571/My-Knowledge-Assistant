package com.example.workbench.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import reactor.core.publisher.Flux;

/**
 * 回归：2026-11-12 线上提问「mybaties是干嘛的」连续失败三次中的两次 ——
 * Spring AI 2.0 流式最后一帧是 usage-only chunk，{@code .map(this::content)}
 * 在 mapper 返回 null 时抛 Reactor NPE，把已输出 243/280 个 chunk 的流整条打断。
 */
class LocalChatClientStreamNullChunkTest {

    @Test
    void dropsUsageOnlyTailChunkInsteadOfThrowingMapperNpe() {
        ChatResponse withText = response("部分回答");
        ChatResponse usageOnly = response(null);

        assertThat(LocalChatClient.textFromResponses(Flux.just(withText, usageOnly))
                .collectList()
                .block())
                .containsExactly("部分回答");
    }

    @Test
    void skipsEmptyTextChunks() {
        assertThat(LocalChatClient.textFromResponses(Flux.just(response(""), response("正文")))
                .collectList()
                .block())
                .containsExactly("正文");
    }

    private ChatResponse response(String text) {
        AssistantMessage output = mock(AssistantMessage.class);
        when(output.getText()).thenReturn(text);
        Generation generation = mock(Generation.class);
        when(generation.getOutput()).thenReturn(output);
        ChatResponse response = mock(ChatResponse.class);
        when(response.getResult()).thenReturn(generation);
        return response;
    }
}
