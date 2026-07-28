package com.example.workbench.rag;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
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
}
