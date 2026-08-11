package com.example.workbench.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.workbench.memory.ConversationMemory;
import com.example.workbench.tools.WebSearchService;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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

    @Test
    void runsDenseAndSparseRetrievalConcurrently() throws Exception {
        CountDownLatch bothStarted = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        VectorStore vectorStore = mock(VectorStore.class);
        SparseRetriever sparseRetriever = mock(SparseRetriever.class);
        when(vectorStore.similaritySearch(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyInt()))
                .thenAnswer(invocation -> awaitOtherRetriever(bothStarted, release));
        when(sparseRetriever.search(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.isNull()))
                .thenAnswer(invocation -> awaitOtherRetriever(bothStarted, release));

        CompletableFuture<RetrievalDebugResponse> response = CompletableFuture.supplyAsync(() ->
                service(vectorStore, sparseRetriever).debugRetrieval("不存在的知识", "user-1"));

        assertThat(bothStarted.await(2, TimeUnit.SECONDS)).isTrue();
        release.countDown();
        assertThat(response.get(2, TimeUnit.SECONDS).candidates()).isEmpty();
    }

    @Test
    void expandsPdfContextAcrossAdjacentPageBoundary() {
        VectorStore vectorStore = mock(VectorStore.class);
        SparseRetriever sparseRetriever = mock(SparseRetriever.class);
        SourceDocument matched = pdfSource("redis", 3, 8, "1. 缓存雪崩及解决方案");
        SourceDocument nextPage = pdfSource("redis", 4, 9, "2. 缓存击穿及解决方案");
        when(sparseRetriever.adjacent("redis", 3, "user-1", "workspace-a"))
                .thenReturn(List.of(nextPage));

        RagService service = service(vectorStore, sparseRetriever, true, 5);
        service.debugRetrieval("redis缓存", "user-1", "workspace-a");

        assertThat(service.expandAdjacent(List.of(matched)))
                .extracting(SourceDocument::content)
                .containsExactly("1. 缓存雪崩及解决方案", "2. 缓存击穿及解决方案");
    }

    private RagService service(VectorStore vectorStore, SparseRetriever sparseRetriever) {
        return service(vectorStore, sparseRetriever, false, 2);
    }

    private RagService service(VectorStore vectorStore, SparseRetriever sparseRetriever,
                               boolean adjacentEnabled, int maxChunksPerDocument) {
        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        beanFactory.addBean("sparseRetriever", sparseRetriever);
        LocalChatClient chatClient = mock(LocalChatClient.class);
        return new RagService(mock(DocumentIngestionService.class), vectorStore, chatClient,
                new ConversationMemory(), mock(WebSearchService.class), new RagQualityGate(chatClient, false),
                true, 5, 0.85, "distance", false, false, 3, false, false,
                beanFactory.getBeanProvider(SparseRetriever.class), true, 60, 4500,
                maxChunksPerDocument, adjacentEnabled);
    }

    private SourceDocument source(String id, String content, double score) {
        return new SourceDocument(id, content, id, id + ".md", "docs/" + id + ".md", 0).withScore(score);
    }

    private SourceDocument pdfSource(String documentId, int chunkIndex, int pageNumber, String content) {
        return new SourceDocument(documentId + "-" + chunkIndex, content, "Redis", "redis.pdf",
                "docs/redis.pdf", chunkIndex, documentId, "redis.pdf", "hash", 0.3, "", 0,
                chunkIndex * 100, chunkIndex * 100 + content.length(), "pdf-page", "SOURCE", "user-1",
                "workspace-a", com.example.workbench.workspace.DocumentVisibility.PRIVATE, pageNumber);
    }

    private List<SourceDocument> awaitOtherRetriever(CountDownLatch bothStarted, CountDownLatch release)
            throws InterruptedException {
        bothStarted.countDown();
        if (!release.await(2, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Dense and Sparse retrieval did not run concurrently");
        }
        return List.of();
    }
}
