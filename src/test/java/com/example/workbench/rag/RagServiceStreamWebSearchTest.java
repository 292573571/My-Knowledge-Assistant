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

/**
 * 锁定 RagService.stream() 流式路径上的两个联网搜索路由：
 * <ul>
 *   <li><b>A</b>：sources 空 / hasEnoughKnowledge=false + webSearchEnabled=true → 切到
 *   {@code streamWithWebSearch}，流式输出 web 答案 + web sources。</li>
 *   <li><b>B</b>：sources 非空 + hasEnoughKnowledge=true + 模型前 30 token 礼貌拒绝 →
 *   立即切到 web search 流式（丢弃本地拒绝话术），最终答案来自 web。</li>
 * </ul>
 *
 * <p>背景：学习助手前端走的是 {@code /api/learning-assistant/sessions/.../messages/stream}
 * （流式接口），之前修复 RagService.chat()（同步接口）漏掉了这条链路。
 */
class RagServiceStreamWebSearchTest {

    /**
     * A 场景：sources 空 + webSearchEnabled=true → 流式走 web search 而非 modelFallback。
     */
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
        // 不应走 modelFallback / LOCAL_KNOWLEDGE 流式
        verify(chatClient, never()).generate(Mockito.anyString());
        List<String> tokens = response.tokens().collectList().block();
        assertThat(tokens).isNotNull();
        assertThat(String.join("", tokens)).contains("来自 Web");
        assertThat(response.sources()).isNotEmpty();
    }

    /**
     * B 场景：sources 非空 + hasEnoughKnowledge=true + 前 30 token 礼貌拒绝 →
     * withPoliteRefusalWebSearchGuard 切断原流 → 切到 web search 流式。
     */
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
        // 第一次 stream() 是 LOCAL_KNOWLEDGE 路径返回礼貌拒绝
        // 第二次 stream() 是 streamWithWebSearch 返回 web 流式答案
        when(chatClient.stream(Mockito.anyString(), Mockito.anyMap()))
                .thenReturn(Flux.just("抱歉，", "我无法", "提供今天的", "热点新闻。"))
                .thenReturn(Flux.just("今日热点包括科技、", "教育等领域。来自 Web。"));
        RagService service = serviceWithWebSearch(vectorStore, chatClient, webSearchService, true);

        RagStreamResponse response = service.stream(new RagChatRequest("conversation-1", "今天的热点新闻"));
        List<String> tokens = response.tokens().collectList().block();
        // 先 block() 触发完整订阅与 fallback，再做 verify
        verify(webSearchService, atLeastOnce()).search("今天的热点新闻");
        verify(chatClient, atLeastOnce()).stream(Mockito.anyString(), Mockito.anyMap());
        assertThat(tokens).isNotNull();
        String fullText = String.join("", tokens);
        // 礼貌拒绝话术应被丢弃，web search 答案应保留
        assertThat(fullText).doesNotContain("抱歉").doesNotContain("我无法");
        assertThat(fullText).contains("来自 Web");
        // 引用应是 web 来源（file="Web: <url>"）而不是本地 PDF（headingPath 含 "Java 面试题"）
        assertThat(response.sources()).isNotEmpty()
                .allSatisfy(source -> {
                    assertThat(source.file()).startsWith("Web:");
                    assertThat(source.headingPath()).doesNotContain("Java 面试题");
                });
    }

    /**
     * B 场景反例：模型正常输出（不命中拒绝模板词）→ 礼貌拒绝兜底不应触发，
     * 不调 webSearchService.search()，web sources 为空。
     */
    @Test
    void streamDoesNotTriggerWebFallbackWhenModelAnswerIsNormal() {
        VectorStore vectorStore = Mockito.mock(VectorStore.class);
        LocalChatClient chatClient = Mockito.mock(LocalChatClient.class);
        WebSearchService webSearchService = Mockito.mock(WebSearchService.class);
        SourceDocument localSource = new SourceDocument(
                "src-1", "今天我们就 Java 集合的应用场景做系统讲解", "Java 面试题整理",
                "doc.pdf", "doc.pdf", 0
        ).withScore(0.3);
        when(vectorStore.similaritySearch(Mockito.anyString(), Mockito.anyInt()))
                .thenReturn(List.of(localSource));
        // 正常流式答案（≥30 字符且不含拒绝模板词）
        when(chatClient.stream(Mockito.anyString(), Mockito.anyMap()))
                .thenReturn(Flux.just("Java 集合框架主要包括 ", "List、Set、Map ", "三大接口类型。"));
        RagService service = serviceWithWebSearch(vectorStore, chatClient, webSearchService, true);

        RagStreamResponse response = service.stream(new RagChatRequest("conversation-1", "今天讲讲 Java 集合"));
        List<String> tokens = response.tokens().collectList().block();
        verify(webSearchService, never()).search(Mockito.anyString());
        assertThat(tokens).isNotNull();
        assertThat(String.join("", tokens)).contains("Java 集合框架");
        // 引用应是本地 PDF（headingPath 含 "Java 面试题"），不是 web
        assertThat(response.sources()).isNotEmpty()
                .allSatisfy(source -> {
                    assertThat(source.headingPath()).contains("Java 面试题");
                    assertThat(source.file()).doesNotStartWith("Web:");
                });
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