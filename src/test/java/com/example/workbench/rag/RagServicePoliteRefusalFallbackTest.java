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
 * 锁定 LOCAL_KNOWLEDGE_MODEL_ANSWER 路径上的"模型礼貌拒绝兜底"重路由：
 * 当模型被无关 PDF 误导而礼貌拒绝（"抱歉，我无法提供..."），且 webSearchEnabled=true
 * 时，应自动改走 answerWithWebSearch 给用户联网答案，而不是交付拒绝模板。
 *
 * <p>背景：实际场景中，问题"今天的热点新闻"被召回到 Java 面试题 PDF（其中含
 * "今天我们就..."引导语），hasEnoughKnowledge 因词法命中返回 true，但模型看到不相关
 * 上下文后礼貌拒绝。新增 isModelPoliteRefusal 兜底可避免用户收到拒绝回复。
 */
class RagServicePoliteRefusalFallbackTest {

    /**
     * 礼貌拒绝场景：chatClient.call() 返回"抱歉，我无法提供..." → 自动重路由到 web search，
     * 最终答案来自 callWithWebResults，包含 web sources。
     */
    @Test
    void modelPoliteRefusalTriggersWebSearch() {
        VectorStore vectorStore = Mockito.mock(VectorStore.class);
        LocalChatClient chatClient = Mockito.mock(LocalChatClient.class);
        WebSearchService webSearchService = Mockito.mock(WebSearchService.class);
        // 含"今天"引导语的 PDF chunk：通过词法命中进入 LOCAL_KNOWLEDGE 路径。
        SourceDocument irrelevantButLexicalHit = new SourceDocument(
                "src-1", "今天我们就 Java 集合的应用场景做系统讲解", "Java 面试题整理",
                "doc.pdf", "doc.pdf", 0
        ).withScore(0.3);
        when(vectorStore.similaritySearch(Mockito.anyString(), Mockito.anyInt()))
                .thenReturn(List.of(irrelevantButLexicalHit));
        // 第一次 call() 返回"礼貌拒绝" → 触发兜底重路由到 web search。
        when(chatClient.call(Mockito.anyString(), Mockito.anyList(), Mockito.anyList(), Mockito.anyMap()))
                .thenReturn("抱歉，我无法提供今天的热点新闻，建议您关注新闻平台获取最新资讯。");
        when(webSearchService.search(Mockito.anyString())).thenReturn(List.of(
                new WebSearchResult("今日热点示例", "https://example.com/news", "今日热点摘要内容")
        ));
        when(chatClient.callWithWebResults(
                Mockito.anyString(), Mockito.anyList(), Mockito.anyList(), Mockito.anyMap()))
                .thenReturn("今日热点包括科技、教育等多个领域的新闻。基于联网搜索的回答包含足够字数。");
        RagService service = serviceWithWebSearch(vectorStore, chatClient, webSearchService, true);

        RagChatResponse response = service.chat(new RagChatRequest("conversation-1", "今天的热点新闻"));

        verify(webSearchService).search("今天的热点新闻");
        verify(chatClient, atLeastOnce()).callWithWebResults(
                Mockito.anyString(), Mockito.anyList(), Mockito.anyList(), Mockito.anyMap());
        assertThat(response.answer()).contains("基于联网搜索");
        assertThat(response.sources()).isNotEmpty();
    }

    /**
     * 正常回答场景：chatClient.call() 返回超过 200 字的长答案 → 不触发礼貌拒绝兜底，
     * 不调用 webSearchService.search()，确保现有正常路径不被新逻辑破坏。
     */
    @Test
    void modelNormalAnswerNotTriggered() {
        VectorStore vectorStore = Mockito.mock(VectorStore.class);
        LocalChatClient chatClient = Mockito.mock(LocalChatClient.class);
        WebSearchService webSearchService = Mockito.mock(WebSearchService.class);
        SourceDocument irrelevantButLexicalHit = new SourceDocument(
                "src-1", "今天我们就 Java 集合的应用场景做系统讲解", "Java 面试题整理",
                "doc.pdf", "doc.pdf", 0
        ).withScore(0.3);
        when(vectorStore.similaritySearch(Mockito.anyString(), Mockito.anyInt()))
                .thenReturn(List.of(irrelevantButLexicalHit));
        // 正常长答案：模型基于本地内容给出有效回答，答案长且包含谦词但远不到 200 字门槛。
        String normalAnswer = "Java 集合框架主要包括 List、Set、Map 三大接口。List 用于存储有序可重复元素，"
                + "常见实现包括 ArrayList（数组实现，查询快）和 LinkedList（链表实现，增删快）；"
                + "Set 用于存储无序不可重复元素，常见实现有 HashSet 和 TreeSet；"
                + "Map 用于存储键值对，常见实现有 HashMap、LinkedHashMap 和 TreeMap。"
                + "在实际项目中可以根据业务对有序性、唯一性和性能的要求选择合适的集合类型。";
        when(chatClient.call(Mockito.anyString(), Mockito.anyList(), Mockito.anyList(), Mockito.anyMap()))
                .thenReturn(normalAnswer);
        RagService service = serviceWithWebSearch(vectorStore, chatClient, webSearchService, true);

        RagChatResponse response = service.chat(new RagChatRequest("conversation-1", "今天讲讲 Java 集合"));

        verify(webSearchService, never()).search(Mockito.anyString());
        verify(chatClient, never()).callWithWebResults(
                Mockito.anyString(), Mockito.anyList(), Mockito.anyList(), Mockito.anyMap());
        assertThat(response.answer()).contains("Java 集合框架");
        assertThat(response.sources()).isNotEmpty();
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