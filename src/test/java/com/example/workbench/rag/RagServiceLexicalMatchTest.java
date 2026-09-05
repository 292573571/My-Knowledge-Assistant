package com.example.workbench.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.example.workbench.memory.ConversationMemory;
import com.example.workbench.tools.WebSearchService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * 中文词法判定的回归测试。
 *
 * <p>中文没有空格分词，而 {@code [\p{IsHan}]{2,}} 是贪婪正则，会把「讲讲缓存」整句当成一个
 * term 去原文里 contains，几乎永远命中不了。结果是检索确实召回了内容，却被判成「知识不足」
 * 转模型兜底——知识库形同虚设。这里锁定切词后的行为，同时守住两条边界：
 * 英文仍按整词匹配、纯疑问词碎片不得作为命中依据。</p>
 */
class RagServiceLexicalMatchTest {

    @Test
    void shortChineseQuestionMatchesTwoCharacterTermInsideLongerPhrase() {
        String question = "讲讲缓存";
        String content = "本章介绍 Redis 缓存的淘汰策略与配置方式。";
        RagChatResponse response = chat(question, content);

        assertThat(response.sources()).isNotEmpty();
        assertThat(response.answer()).contains("缓存");
    }

    @Test
    void chineseQuestionMatchesWhenDocumentUsesDifferentWordingAroundTheTerm() {
        String question = "索引怎么建";
        String content = "为加快查询速度，需要在常用字段上建立合适的索引结构。";
        RagChatResponse response = chat(question, content);

        assertThat(response.sources()).isNotEmpty();
    }

    @Test
    void englishTermsStillMatchWholeWordsOnly() {
        String question = "explain redis cluster";
        String content = "Redis Cluster 的分片与故障转移说明。";
        RagChatResponse response = chat(question, content);

        assertThat(response.sources()).isNotEmpty();
    }

    @Test
    void unrelatedQuestionStillFallsBackToModel() {
        String question = "今天天气怎么样";
        String content = "Redis 缓存淘汰策略的配置说明。";
        RagChatResponse response = chat(question, content);

        assertThat(response.sources()).isEmpty();
    }

    @Test
    void questionWordFragmentsDoNotCountAsKnowledgeMatch() {
        String question = "什么是缓存";
        String content = "本文档说明了什么叫做配置管理。";
        RagChatResponse response = chat(question, content);

        assertThat(response.sources()).isEmpty();
    }

    private RagChatResponse chat(String question, String content) {
        VectorStore vectorStore = Mockito.mock(VectorStore.class);
        LocalChatClient chatClient = Mockito.mock(LocalChatClient.class);
        SourceDocument source = new SourceDocument(
                "doc-1", content, "章节", "handbook.pdf", "docs/handbook.pdf", 0).withScore(0.1);
        when(vectorStore.similaritySearch(Mockito.anyString(), Mockito.anyInt()))
                .thenReturn(List.of(source));
        when(chatClient.generate(Mockito.anyString(), Mockito.anyList(), Mockito.anyMap()))
                .thenReturn("UNRELATED");
        when(chatClient.call(Mockito.anyString(), Mockito.anyList(), Mockito.anyList(), Mockito.anyMap()))
                .thenReturn(content);
        RagQualityGate qualityGate = Mockito.mock(RagQualityGate.class);
        when(qualityGate.relevantSources(Mockito.anyString(), Mockito.anyList()))
                .thenAnswer(invocation -> invocation.getArgument(1));
        when(qualityGate.approvesAnswer(Mockito.anyString(), Mockito.anyString(), Mockito.anyList()))
                .thenReturn(true);
        RagService service = new RagService(
                Mockito.mock(DocumentIngestionService.class), vectorStore, chatClient,
                new ConversationMemory(), Mockito.mock(WebSearchService.class), qualityGate,
                false, 5, 0.45, "distance", false, false, 4, true, false);

        return service.chat(new RagChatRequest("conversation-lexical", question));
    }
}
