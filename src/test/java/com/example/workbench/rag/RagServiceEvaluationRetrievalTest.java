package com.example.workbench.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.workbench.memory.ChatMessage;
import com.example.workbench.memory.ConversationMemory;
import com.example.workbench.tools.WebSearchService;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

/**
 * 锁定评测检索的可见性边界：评测只允许命中 PUBLIC 文档。
 *
 * <p>背景：评测 conversationId 形如 {@code user-1:eval-draft-010}，原实现会从中解析出
 * owner=1，使评测账号自己的 PRIVATE 文档进入上下文。后果有两个：运营往评测账号个人空间
 * 上传文档就会改变门禁基线分数；SECURITY 套件（验证私有内容不会被公开检索命中）会因
 * 「自己读自己的私有文档」而恒通过，等于没测。</p>
 */
class RagServiceEvaluationRetrievalTest {

    @Test
    void evaluationRetrievalIgnoresConversationOwnerSoPrivateDocumentsNeverEnterContext() {
        ScopedVectorStore vectorStore = mock(ScopedVectorStore.class);
        SparseRetriever sparseRetriever = mock(SparseRetriever.class);
        stubRetrieval(vectorStore, sparseRetriever);

        RagService service = service(vectorStore, sparseRetriever);
        service.chatForEvaluation(new RagChatRequest("user-1:eval-draft-010", "Cordis 是什么？"),
                new RagChatOptions(false, false));

        ArgumentCaptor<String> denseOwner = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> sparseOwner = ArgumentCaptor.forClass(String.class);
        verify(vectorStore).similaritySearch(anyString(), anyInt(), denseOwner.capture(), anyScopes());
        verify(sparseRetriever).search(anyString(), anyInt(), sparseOwner.capture(), anyScopes());

        assertThat(denseOwner.getValue()).isEmpty();
        assertThat(sparseOwner.getValue()).isEmpty();
    }

    @Test
    void normalChatStillRetrievesOwnersPrivateDocuments() {
        ScopedVectorStore vectorStore = mock(ScopedVectorStore.class);
        SparseRetriever sparseRetriever = mock(SparseRetriever.class);
        stubRetrieval(vectorStore, sparseRetriever);

        RagService service = service(vectorStore, sparseRetriever);
        service.chat(new RagChatRequest("user-1:conversation-1", "Cordis 是什么？"),
                new RagChatOptions(false, false));

        ArgumentCaptor<String> denseOwner = ArgumentCaptor.forClass(String.class);
        verify(vectorStore).similaritySearch(anyString(), anyInt(), denseOwner.capture(), anyScopes());
        assertThat(denseOwner.getValue()).isEqualTo("1");
    }

    private void stubRetrieval(ScopedVectorStore vectorStore, SparseRetriever sparseRetriever) {
        when(vectorStore.similaritySearch(anyString(), anyInt(), anyString(), anyScopes()))
                .thenReturn(List.of(publicSource()));
        when(sparseRetriever.search(anyString(), anyInt(), anyString(), anyScopes())).thenReturn(List.of());
    }

    private Set<String> anyScopes() {
        return ArgumentMatchers.<Set<String>>isNull();
    }

    private RagService service(ScopedVectorStore vectorStore, SparseRetriever sparseRetriever) {
        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        beanFactory.addBean("sparseRetriever", sparseRetriever);
        LocalChatClient chatClient = mock(LocalChatClient.class);
        when(chatClient.call(anyString(), ArgumentMatchers.<SourceDocument>anyList(),
                ArgumentMatchers.<ChatMessage>anyList(), ArgumentMatchers.<String, String>anyMap()))
                .thenReturn("公开文档里提到的内容，用于让回答通过质量门禁的示例文本。");
        RagQualityGate qualityGate = mock(RagQualityGate.class);
        when(qualityGate.relevantSources(anyString(), ArgumentMatchers.<SourceDocument>anyList()))
                .thenAnswer(invocation -> invocation.getArgument(1));
        when(qualityGate.approvesAnswer(anyString(), anyString(),
                ArgumentMatchers.<SourceDocument>anyList())).thenReturn(true);
        return new RagService(mock(DocumentIngestionService.class), vectorStore, chatClient,
                new ConversationMemory(), mock(WebSearchService.class), qualityGate,
                true, 5, 0.85, "distance", false, false, 3, false, false,
                beanFactory.getBeanProvider(SparseRetriever.class), true, 60, 4500, 2, false);
    }

    private SourceDocument publicSource() {
        return new SourceDocument("doc-1", "Cordis 是插件框架，负责插件装载与卸载。", "DeepSeek Harness",
                "public.pdf", "docs/public.pdf", 0, "doc-1", "public.pdf", "hash", 0.2, "", 0, 0, 10,
                "pdf-page", "SOURCE", "user-2", "workspace-a",
                com.example.workbench.workspace.DocumentVisibility.PUBLIC, 1);
    }
}
