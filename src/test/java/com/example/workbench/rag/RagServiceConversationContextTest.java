package com.example.workbench.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.workbench.memory.ChatMessage;
import com.example.workbench.memory.ConversationMemory;
import com.example.workbench.tools.WebSearchService;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 覆盖「历史是否带入本次回答」的判定：指代词只影响是否改写检索问题，不再决定历史去留。
 */
class RagServiceConversationContextTest {

    private static final List<ChatMessage> HISTORY = List.of(
            new ChatMessage("user", "什么是 RAG？"),
            new ChatMessage("assistant", "RAG 是检索增强生成，先检索资料再生成答案。"));

    private static RagService service(LocalChatClient chatClient) {
        return new RagService(mock(DocumentIngestionService.class), mock(VectorStore.class), chatClient,
                new ConversationMemory(), mock(WebSearchService.class), mock(RagQualityGate.class),
                false, 5, 0.85, "distance", false, false, 3, true, false);
    }

    @Test
    void keepsHistoryWhenQuestionHasNoDeicticTerm() {
        ContextEvaluationResult result = service(mock(LocalChatClient.class))
                .evaluateContext(new ContextEvaluationRequest(
                        "conv-1", "性能优化怎么做？", HISTORY, new RagChatOptions(false, false)));

        assertThat(result.relation()).isEqualTo(ContextRelation.RELATED);
        assertThat(result.relevantHistory()).hasSize(2);
        assertThat(result.standaloneQuestion()).isNull();
    }

    @Test
    void keepsHistoryWhenModelJudgesIndependent() {
        LocalChatClient chatClient = mock(LocalChatClient.class);
        when(chatClient.generate(anyString(), anyList(), anyMap())).thenReturn("INDEPENDENT");

        ContextEvaluationResult result = service(chatClient)
                .evaluateContext(new ContextEvaluationRequest(
                        "conv-1", "它怎么优化？", HISTORY, new RagChatOptions(false, false)));

        assertThat(result.relation()).isEqualTo(ContextRelation.RELATED);
        assertThat(result.relevantHistory()).hasSize(2);
        assertThat(result.standaloneQuestion()).isNull();
    }

    @Test
    void rewritesQuestionAndKeepsHistoryWhenDeicticTermPresent() {
        LocalChatClient chatClient = mock(LocalChatClient.class);
        when(chatClient.generate(anyString(), anyList(), anyMap())).thenReturn("RELATED: RAG 的性能如何优化？");

        ContextEvaluationResult result = service(chatClient)
                .evaluateContext(new ContextEvaluationRequest(
                        "conv-1", "它怎么优化？", HISTORY, new RagChatOptions(false, false)));

        assertThat(result.relation()).isEqualTo(ContextRelation.RELATED);
        assertThat(result.relevantHistory()).hasSize(2);
        assertThat(result.standaloneQuestion()).isEqualTo("RAG 的性能如何优化？");
    }

    @Test
    void staysIndependentWhenHistoryIsEmpty() {
        ContextEvaluationResult result = service(mock(LocalChatClient.class))
                .evaluateContext(new ContextEvaluationRequest(
                        "conv-1", "当前知识库支持哪些文档格式？", List.of(), new RagChatOptions(false, false)));

        assertThat(result.relation()).isEqualTo(ContextRelation.INDEPENDENT);
        assertThat(result.relevantHistory()).isEmpty();
    }
}
