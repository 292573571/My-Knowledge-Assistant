package com.example.workbench.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.workbench.memory.ConversationMemory;
import com.example.workbench.tools.WebSearchResult;
import com.example.workbench.tools.WebSearchService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * 锁定 {@code sources.isEmpty()} 分支的联网搜索路由：开启 webSearch 时必须调用博查，
 * 不开启时保持原 modelFallback 行为。
 */
class RagServiceWebSearchTest {

    @Test
    void emptySourcesRoutesToWebSearchWhenWebSearchEnabled() {
        VectorStore vectorStore = Mockito.mock(VectorStore.class);
        LocalChatClient chatClient = Mockito.mock(LocalChatClient.class);
        WebSearchService webSearchService = Mockito.mock(WebSearchService.class);
        when(vectorStore.similaritySearch(Mockito.anyString(), Mockito.anyInt())).thenReturn(List.of());
        when(webSearchService.search(Mockito.anyString())).thenReturn(List.of(
                new WebSearchResult("示例标题", "https://example.com/page", "摘要内容")
        ));
        when(chatClient.callWithWebResults(
                Mockito.anyString(), Mockito.anyList(), Mockito.anyList(), Mockito.anyMap()))
                .thenReturn("基于联网搜索的回答");
        RagService service = serviceWithWebSearch(vectorStore, chatClient, webSearchService, true);

        RagChatResponse response = service.chat(new RagChatRequest("conversation-1", "2026年9月9日新闻"));

        verify(webSearchService).search("2026年9月9日新闻");
        verify(chatClient, never()).generate(Mockito.anyString());
        verify(chatClient, never()).call(Mockito.anyString(), Mockito.anyList(), Mockito.anyList(), Mockito.anyMap());
        assertThat(response.answer()).contains("基于联网搜索的回答");
        assertThat(response.sources()).isNotEmpty();
    }

    @Test
    void emptySourcesFallsBackToModelWhenWebSearchDisabled() {
        VectorStore vectorStore = Mockito.mock(VectorStore.class);
        LocalChatClient chatClient = Mockito.mock(LocalChatClient.class);
        WebSearchService webSearchService = Mockito.mock(WebSearchService.class);
        when(vectorStore.similaritySearch(Mockito.anyString(), Mockito.anyInt())).thenReturn(List.of());
        when(chatClient.generate(Mockito.anyString())).thenReturn("纯模型直答的回答，包含足够字数的内容以通过校验。");
        RagService service = serviceWithWebSearch(vectorStore, chatClient, webSearchService, false);

        RagChatResponse response = service.chat(new RagChatRequest("conversation-1", "2026年9月9日新闻"));

        verify(webSearchService, never()).search(Mockito.anyString());
        verify(chatClient, atLeastOnce()).generate(Mockito.anyString());
        assertThat(response.answer()).contains("纯模型直答").contains("通用大模型知识");
        assertThat(response.sources()).isEmpty();
    }

    private RagService serviceWithWebSearch(
            VectorStore vectorStore,
            LocalChatClient chatClient,
            WebSearchService webSearchService,
            boolean webSearchEnabled) {
        RagQualityGate qualityGate = Mockito.mock(RagQualityGate.class);
        when(qualityGate.relevantSources(Mockito.anyString(), Mockito.anyList()))
                .thenAnswer(invocation -> invocation.getArgument(1));
        when(qualityGate.approvesAnswer(Mockito.anyString(), Mockito.anyString(), Mockito.anyList()))
                .thenReturn(true);
        return new RagService(
                Mockito.mock(DocumentIngestionService.class),
                vectorStore,
                chatClient,
                new ConversationMemory(),
                webSearchService,
                qualityGate,
                false,
                5,
                0.45,
                "distance",
                false,
                false,
                4,
                true,
                webSearchEnabled
        );
    }
}