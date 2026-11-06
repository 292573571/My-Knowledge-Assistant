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
        // 联网搜索答案不再透出博查 URL 引用（前端展示"Web: ..."无意义）
        assertThat(response.sources()).isEmpty();
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

    @Test
    void evaluationPathSkipsWebSearchEvenWhenGloballyEnabled() {
        // 评测链路显式关闭联网兜底：门禁只衡量本地知识库能力，不能靠联网搜索凑答案。
        VectorStore vectorStore = Mockito.mock(VectorStore.class);
        LocalChatClient chatClient = Mockito.mock(LocalChatClient.class);
        WebSearchService webSearchService = Mockito.mock(WebSearchService.class);
        when(vectorStore.similaritySearch(Mockito.anyString(), Mockito.anyInt())).thenReturn(List.of());
        when(chatClient.generate(Mockito.anyString())).thenReturn("纯模型直答的回答，包含足够字数的内容以通过校验。");
        RagService service = serviceWithWebSearch(vectorStore, chatClient, webSearchService, true);

        RagChatResponse response = service.chatForEvaluation(
                new RagChatRequest("eval-case-1", "手册目录中数据源管理在哪一页"),
                new RagChatOptions(false, false, false));

        verify(webSearchService, never()).search(Mockito.anyString());
        assertThat(response.sources()).isEmpty();
    }

    @Test
    void evaluationPathSkipsAnswerGroundingGate() {
        // 评测链路旁路答案接地质量门：质量门 LLM 裁决有抖动，会把有依据的答案误判 FAIL
        // 并强转兜底清空 sources，污染评测指标（线上聊天行为不变，质量门仍然生效）。
        VectorStore vectorStore = Mockito.mock(VectorStore.class);
        LocalChatClient chatClient = Mockito.mock(LocalChatClient.class);
        WebSearchService webSearchService = Mockito.mock(WebSearchService.class);
        when(vectorStore.similaritySearch(Mockito.anyString(), Mockito.anyInt())).thenReturn(List.of(
                new SourceDocument("doc-1", "转换：主要用于数据的处理，是数据流的基础。作业：作业是更加高级的操作，是步骤流。",
                        "kellte手册", "kellte开发指导说明书.docx", "docs/kellte.docx", 4)));
        when(chatClient.call(Mockito.anyString(), Mockito.anyList(), Mockito.anyList(), Mockito.anyMap()))
                .thenReturn("转换负责数据流处理，作业负责流程控制，作业比转换更高级。");
        RagQualityGate qualityGate = Mockito.mock(RagQualityGate.class);
        when(qualityGate.relevantSources(Mockito.anyString(), Mockito.anyList()))
                .thenAnswer(invocation -> invocation.getArgument(1));
        when(qualityGate.approvesAnswer(Mockito.anyString(), Mockito.anyString(), Mockito.anyList()))
                .thenReturn(false);
        RagService service = serviceWithWebSearch(vectorStore, chatClient, webSearchService, true, qualityGate);

        RagChatResponse response = service.chatForEvaluation(
                new RagChatRequest("eval-case-2", "Kettle 里转换和作业有什么区别？"),
                new RagChatOptions(false, false, false, false));

        // 质量门的 LLM 裁决（候选筛选 + 答案接地）都不应被调用：
        // 即使它要判 FAIL/KEEP:none，评测链路也直接采信本地规则过滤后的候选与生成答案。
        verify(qualityGate, never()).relevantSources(Mockito.anyString(), Mockito.anyList());
        verify(qualityGate, never()).approvesAnswer(Mockito.anyString(), Mockito.anyString(), Mockito.anyList());
        assertThat(response.sources()).isNotEmpty();
        assertThat(response.answer()).contains("转换");
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
        return serviceWithWebSearch(vectorStore, chatClient, webSearchService, webSearchEnabled, qualityGate);
    }

    private RagService serviceWithWebSearch(
            VectorStore vectorStore,
            LocalChatClient chatClient,
            WebSearchService webSearchService,
            boolean webSearchEnabled,
            RagQualityGate qualityGate) {
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