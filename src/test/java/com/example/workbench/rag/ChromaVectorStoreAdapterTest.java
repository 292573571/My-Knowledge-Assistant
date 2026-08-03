package com.example.workbench.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

import com.example.workbench.workspace.DocumentVisibility;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.beans.factory.ObjectProvider;

class ChromaVectorStoreAdapterTest {

    @Test
    void clearDeletesAllProjectDocumentsWithoutRelyingOnProcessLocalIds() {
        @SuppressWarnings("unchecked")
        ObjectProvider<org.springframework.ai.vectorstore.VectorStore> provider = Mockito.mock(ObjectProvider.class);
        org.springframework.ai.vectorstore.VectorStore chroma = Mockito.mock(org.springframework.ai.vectorstore.VectorStore.class);
        InMemoryVectorStore fallback = Mockito.mock(InMemoryVectorStore.class);
        Mockito.when(provider.getIfAvailable()).thenReturn(chroma);
        ChromaVectorStoreAdapter adapter = new ChromaVectorStoreAdapter(provider, fallback);

        adapter.clear();

        verify(fallback).clear();
        verify(chroma).delete(any(Filter.Expression.class));
    }

    @Test
    void scopedSearchSendsVisibilityOwnerAndWorkspaceFilterToChroma() {
        @SuppressWarnings("unchecked")
        ObjectProvider<org.springframework.ai.vectorstore.VectorStore> provider = Mockito.mock(ObjectProvider.class);
        org.springframework.ai.vectorstore.VectorStore chroma = Mockito.mock(org.springframework.ai.vectorstore.VectorStore.class);
        InMemoryVectorStore fallback = new InMemoryVectorStore();
        Mockito.when(provider.getIfAvailable()).thenReturn(chroma);
        Mockito.when(chroma.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(
                new org.springframework.ai.document.Document("result", "matching content", java.util.Map.of(
                        "id", "result", "visibility", "PUBLIC", "workspaceId", "public-default"))));
        ChromaVectorStoreAdapter adapter = new ChromaVectorStoreAdapter(provider, fallback);

        adapter.similaritySearch("matching", 5, "user-1", "team-1");

        ArgumentCaptor<SearchRequest> captor = ArgumentCaptor.forClass(SearchRequest.class);
        verify(chroma).similaritySearch(captor.capture());
        SearchRequest request = captor.getValue();
        assertThat(request.hasFilterExpression()).isTrue();
        assertThat(request.getFilterExpression().toString())
                .contains("visibility", "PUBLIC", "PRIVATE", "WORKSPACE", "ownerUserId", "user-1", "workspaceId", "team-1");
    }

    @Test
    void scopedFallbackNeverReturnsAnotherWorkspaceOrOwnersPrivateDocument() {
        @SuppressWarnings("unchecked")
        ObjectProvider<org.springframework.ai.vectorstore.VectorStore> provider = Mockito.mock(ObjectProvider.class);
        InMemoryVectorStore fallback = new InMemoryVectorStore();
        fallback.addAll(List.of(
                source("public", "", "public-default", DocumentVisibility.PUBLIC),
                source("own-private", "user-1", "team-1", DocumentVisibility.PRIVATE),
                source("other-private", "user-2", "team-1", DocumentVisibility.PRIVATE),
                source("own-workspace", "user-2", "team-1", DocumentVisibility.WORKSPACE),
                source("other-workspace", "user-2", "team-2", DocumentVisibility.WORKSPACE)
        ));
        ChromaVectorStoreAdapter adapter = new ChromaVectorStoreAdapter(provider, fallback);

        assertThat(adapter.similaritySearch("shared", 10, "user-1", "team-1"))
                .extracting(SourceDocument::id)
                .containsExactlyInAnyOrder("public", "own-private", "own-workspace");
    }

    @Test
    void writesPdfPageNumberToChromaMetadata() {
        @SuppressWarnings("unchecked")
        ObjectProvider<org.springframework.ai.vectorstore.VectorStore> provider = Mockito.mock(ObjectProvider.class);
        org.springframework.ai.vectorstore.VectorStore chroma = Mockito.mock(org.springframework.ai.vectorstore.VectorStore.class);
        Mockito.when(provider.getIfAvailable()).thenReturn(chroma);
        ChromaVectorStoreAdapter adapter = new ChromaVectorStoreAdapter(provider, new InMemoryVectorStore());
        SourceDocument pdfSource = new SourceDocument(
                "pdf#chunk-0", "page content", "Guide", "guide.pdf", "docs/guide.pdf", 0,
                "pdf", "guide.pdf", "hash", 0, "", 0, 0, 12,
                "pdf-page", "SOURCE", "", "public-default", DocumentVisibility.PUBLIC, 3
        );

        adapter.addAll(List.of(pdfSource));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Document>> documents = ArgumentCaptor.forClass(List.class);
        verify(chroma).add(documents.capture());
        assertThat(documents.getValue()).singleElement().satisfies(document ->
                assertThat(document.getMetadata()).containsEntry("pageNumber", 3)
        );
    }

    @Test
    void propagatesChromaWriteFailureForTaskRetry() {
        @SuppressWarnings("unchecked")
        ObjectProvider<org.springframework.ai.vectorstore.VectorStore> provider = Mockito.mock(ObjectProvider.class);
        org.springframework.ai.vectorstore.VectorStore chroma = Mockito.mock(org.springframework.ai.vectorstore.VectorStore.class);
        Mockito.when(provider.getIfAvailable()).thenReturn(chroma);
        Mockito.doThrow(new IllegalStateException("unavailable")).when(chroma).add(Mockito.anyList());
        ChromaVectorStoreAdapter adapter = new ChromaVectorStoreAdapter(provider, new InMemoryVectorStore());

        assertThatThrownBy(() -> adapter.addAll(List.of(source("one", "", "public-default", DocumentVisibility.PUBLIC))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Chroma");
    }

    private SourceDocument source(String id, String owner, String workspaceId, DocumentVisibility visibility) {
        return new SourceDocument(id, "shared knowledge", id, id + ".md", "docs/" + id + ".md", 0,
                id + "-doc", id + ".md", id + "-hash", 0, id, 1, 0, 16,
                "text-paragraph", "SOURCE", owner, workspaceId, visibility);
    }
}
