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
import reactor.core.publisher.Flux;

class RagServiceStreamWebSearchTest {

    @Test
    void streamRoutesToWebSearchWhenSourcesEmptyAndWebSearchEnabled() {
        VectorStore vectorStore = Mockito.mock(VectorStore.class);
        LocalChatClient chatClient = Mockito.mock(LocalChatClient.class);
        WebSearchService webSearchService = Mockito.mock(WebSearchService.class);
        when(vectorStore.similaritySearch(Mockito.anyString(), Mockito.anyInt())).thenReturn(List.of());
        when(webSearchService.search(Mockito.anyString())).thenReturn(List.of(
                new WebSearchResult("示例标题", "https://example.com/page", "摘要内容")
        ));
        when(chatClient.stream(Mockito.anyString(), Mockito.anyMap()))
                .thenReturn(Flux.just("今日热点包括科技、教育等领域。来自 Web。"));
        RagService service = serviceWithWebSearch(vectorStore, chatClient, webSearchService, true);

        RagStreamResponse response = service.stream(new RagChatRequest("conversation-1", "今天的热点新闻"));

        verify(webSearchService).search("今天的热点新闻");
        verify(chatClient, never()).generate(Mockito.anyString());
        assertThat(String.join("", response.tokens().collectList().block())).contains("来自 Web");
        assertThat(response.sources()).isEmpty();
        assertThat(response.route()).isEqualTo(RagAnswerRoute.WEB_FALLBACK);
    }

    @Test
    void streamSwitchesToWebSearchWhenModelEmitsPoliteRefusalUpfront() {
        VectorStore vectorStore = Mockito.mock(VectorStore.class);
        LocalChatClient chatClient = Mockito.mock(LocalChatClient.class);
        WebSearchService webSearchService = Mockito.mock(WebSearchService.class);
        SourceDocument lexicalButIrrelevant = new SourceDocument(
                "src-1", "今天我们就 Java 集合的应用场景做系统讲解", "Java 面试题整理",
                "doc.pdf", "doc.pdf", 0
        ).withScore(0.3);
        when(vectorStore.similaritySearch(Mockito.anyString(), Mockito.anyInt()))
                .thenReturn(List.of(lexicalButIrrelevant));
        when(webSearchService.search(Mockito.anyString())).thenReturn(List.of(
                new WebSearchResult("今日热点示例", "https://example.com/news", "今日热点摘要内容")
        ));
        when(chatClient.stream(Mockito.anyString(), Mockito.anyMap()))
                .thenReturn(Flux.just("抱歉，", "我无法", "提供今天的", "热点新闻。"))
                .thenReturn(Flux.just("今日热点包括科技、", "教育等领域。来自 Web。"));
        RagService service = serviceWithWebSearch(vectorStore, chatClient, webSearchService, true);

        RagStreamResponse response = service.stream(new RagChatRequest("conversation-1", "今天的热点新闻"));

        String fullText = String.join("", response.tokens().collectList().block());
        verify(webSearchService, atLeastOnce()).search("今天的热点新闻");
        assertThat(fullText).doesNotContain("抱歉").doesNotContain("我无法").contains("来自 Web");
        assertThat(response.sources()).isEmpty();
        assertThat(response.route()).isEqualTo(RagAnswerRoute.WEB_FALLBACK);
    }

    @Test
    void streamDoesNotTriggerWebFallbackWhenModelAnswerIsNormal() {
        VectorStore vectorStore = Mockito.mock(VectorStore.class);
        LocalChatClient chatClient = Mockito.mock(LocalChatClient.class);
        WebSearchService webSearchService = Mockito.mock(WebSearchService.class);
        SourceDocument localSource = new SourceDocument(
                "src-1", "今天我们就 Java 集合的应用场景做系统讲解", "Java 面试题整理",
                "doc.pdf", "doc.pdf", 0
        ).withScore(0.3);
        when(vectorStore.similaritySearch(Mockito.anyString(), Mockito.anyInt())).thenReturn(List.of(localSource));
        when(chatClient.stream(Mockito.anyString(), Mockito.anyMap()))
                .thenReturn(Flux.just("Java 集合框架主要包括 ", "List、Set、Map ", "三大接口类型。"));
        RagService service = serviceWithWebSearch(vectorStore, chatClient, webSearchService, true);

        RagStreamResponse response = service.stream(new RagChatRequest("conversation-1", "今天讲讲 Java 集合"));

        String fullText = String.join("", response.tokens().collectList().block());
        verify(webSearchService, never()).search(Mockito.anyString());
        assertThat(fullText).contains("Java 集合框架");
        assertThat(response.sources()).isNotEmpty().allSatisfy(source -> {
            assertThat(source.headingPath()).contains("Java 面试题");
            assertThat(source.file()).doesNotStartWith("Web:");
        });
        assertThat(response.route()).isEqualTo(RagAnswerRoute.LOCAL_KNOWLEDGE);
    }

    @Test
    void completedLocalAnswerThatFailsGroundingHasNoCitation() {
        VectorStore vectorStore = Mockito.mock(VectorStore.class);
        LocalChatClient chatClient = Mockito.mock(LocalChatClient.class);
        WebSearchService webSearchService = Mockito.mock(WebSearchService.class);
        RagQualityGate qualityGate = Mockito.mock(RagQualityGate.class);
        SourceDocument localSource = new SourceDocument(
                "src-1", "SSL 用于保护网络通信。", "网络安全", "security.pdf", "docs/security.pdf", 1
        ).withScore(0.1);
        when(vectorStore.similaritySearch(Mockito.anyString(), Mockito.anyInt())).thenReturn(List.of(localSource));
        when(chatClient.stream(Mockito.anyString(), Mockito.anyMap()))
                .thenReturn(Flux.just("这是一个无法由资料支持的回答。"));
        when(qualityGate.isEnabled()).thenReturn(true);
        when(qualityGate.relevantSources(Mockito.anyString(), Mockito.anyList()))
                .thenAnswer(invocation -> invocation.getArgument(1));
        when(qualityGate.approvesAnswer(Mockito.anyString(), Mockito.anyString(), Mockito.anyList()))
                .thenReturn(false);
        RagService service = new RagService(
                Mockito.mock(DocumentIngestionService.class), vectorStore, chatClient,
                new ConversationMemory(), webSearchService, qualityGate,
                false, 5, 0.45, "distance", false, false, 4, true, false);

        RagStreamResponse response = service.stream(new RagChatRequest("conversation-1", "SSL 是什么？"));

        assertThat(String.join("", response.tokens().collectList().block()))
                .isEqualTo("这是一个无法由资料支持的回答。");
        assertThat(response.finalSources("这是一个无法由资料支持的回答。")).isEmpty();
        assertThat(response.route()).isEqualTo(RagAnswerRoute.NO_KNOWLEDGE);
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
                Mockito.mock(DocumentIngestionService.class), vectorStore, chatClient,
                new ConversationMemory(), webSearchService, qualityGate,
                false, 5, 0.45, "distance", false, false, 4, true, webSearchEnabled);
    }
}
