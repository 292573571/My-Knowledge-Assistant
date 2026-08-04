package com.example.workbench.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.workbench.memory.ConversationMemory;
import com.example.workbench.tools.WebSearchService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

class RagServiceHybridRetrievalTest {

    @Test
    void fusesDenseAndSparseRanksWithoutOverwritingDenseDistance() {
        SourceDocument sharedDense = source("shared", "ERR-421 connection rejected", 0.30);
        SourceDocument denseOnly = source("dense-only", "generic connection handling", 0.35);
        SourceDocument sharedSparse = sharedDense.withScore(7.0);
        SourceDocument sparseOnly = source("sparse-only", "ERR-421 product P-17", 5.0);
        VectorStore vectorStore = mock(VectorStore.class);
        SparseRetriever sparseRetriever = mock(SparseRetriever.class);
        when(vectorStore.similaritySearch(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(List.of(sharedDense, denseOnly));
        when(sparseRetriever.search(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.isNull()))
                .thenReturn(List.of(sharedSparse, sparseOnly));
        when(sparseRetriever.adjacent(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.isNull()))
                .thenReturn(List.of());

        RetrievalDebugResponse response = service(vectorStore, sparseRetriever)
                .debugRetrieval("ERR-421", "user-1");

        assertThat(response.candidates()).extracting(RetrievalDebug::retrievalChannel)
                .contains("HYBRID", "DENSE", "SPARSE");
        RetrievalDebug hybrid = response.candidates().stream()
                .filter(candidate -> candidate.retrievalChannel().equals("HYBRID"))
                .findFirst()
                .orElseThrow();
        assertThat(hybrid.score()).isEqualTo(0.30);
        assertThat(hybrid.denseScore()).isEqualTo(0.30);
        assertThat(hybrid.sparseScore()).isEqualTo(7.0);
        assertThat(hybrid.denseRank()).isEqualTo(1);
        assertThat(hybrid.sparseRank()).isEqualTo(1);
        assertThat(hybrid.finalRank()).isEqualTo(1);
        assertThat(hybrid.usedInContext()).isTrue();
    }

    @Test
    void sparseOnlyExactIdentifierCanEnterContextWithoutDenseDistanceThreshold() {
        VectorStore vectorStore = mock(VectorStore.class);
        SparseRetriever sparseRetriever = mock(SparseRetriever.class);
        when(vectorStore.similaritySearch(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(List.of());
        when(sparseRetriever.search(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.isNull()))
                .thenReturn(List.of(source("sparse", "产品编号 P-17 对应支付网关", 6.0)));
        when(sparseRetriever.adjacent(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.isNull()))
                .thenReturn(List.of());

        RetrievalDebugResponse response = service(vectorStore, sparseRetriever)
                .debugRetrieval("P-17", "user-1");

        assertThat(response.candidates()).singleElement().satisfies(candidate -> {
            assertThat(candidate.retrievalChannel()).isEqualTo("SPARSE");
            assertThat(candidate.denseScore()).isNull();
            assertThat(candidate.sparseScore()).isEqualTo(6.0);
            assertThat(candidate.usedInContext()).isTrue();
        });
    }

    private RagService service(VectorStore vectorStore, SparseRetriever sparseRetriever) {
        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        beanFactory.addBean("sparseRetriever", sparseRetriever);
        LocalChatClient chatClient = mock(LocalChatClient.class);
        return new RagService(mock(DocumentIngestionService.class), vectorStore, chatClient,
                new ConversationMemory(), mock(WebSearchService.class), new RagQualityGate(chatClient, false),
                true, 5, 0.85, "distance", false, false, 3, false, false,
                beanFactory.getBeanProvider(SparseRetriever.class), true, 60, 3000, 2, false);
    }

    private SourceDocument source(String id, String content, double score) {
        return new SourceDocument(id, content, id, id + ".md", "docs/" + id + ".md", 0).withScore(score);
    }
}
