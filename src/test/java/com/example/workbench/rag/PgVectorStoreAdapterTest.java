package com.example.workbench.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.workbench.workspace.DocumentVisibility;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

class PgVectorStoreAdapterTest {

    private DocumentChunkRepository repository;
    private EmbeddingModel embeddingModel;
    private EntityManager entityManager;
    private PgVectorStoreAdapter adapter;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        repository = mock(DocumentChunkRepository.class);
        embeddingModel = mock(EmbeddingModel.class);
        ObjectProvider<EmbeddingModel> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(embeddingModel);
        entityManager = mock(EntityManager.class);
        adapter = new PgVectorStoreAdapter(repository, provider);
        ReflectionTestUtils.setField(adapter, "entityManager", entityManager);
    }

    private SourceDocument sampleChunk(String id, String content) {
        return new SourceDocument(id, content, "标题", "源文件.md", "docs/源文件.md", 0,
                "doc-1", "源文件.md", "hash-1", 0.0, "标题", 1, 0, content.length(),
                "text-paragraph", "SOURCE", "1", "ws-1", DocumentVisibility.PUBLIC, 0);
    }

    @Test
    void addAllEmbedsThenWritesRowsAndVectorUpdates() {
        when(embeddingModel.embed(anyList())).thenReturn(List.of(
                new float[]{0.1f, 0.2f}, new float[]{0.3f, 0.4f}));
        Query update = mock(Query.class);
        when(entityManager.createNativeQuery(anyString())).thenReturn(update);
        when(update.setParameter(anyString(), any())).thenReturn(update);

        adapter.addAll(List.of(sampleChunk("doc-1#chunk-0", "第一段"), sampleChunk("doc-1#chunk-1", "第二段")));

        verify(repository).deleteAllByIdInBatch(List.of("doc-1#chunk-0", "doc-1#chunk-1"));
        verify(repository).saveAll(anyList());
        verify(repository).flush();
        verify(update, org.mockito.Mockito.times(2)).executeUpdate();
    }

    @Test
    void addAllWithEmptyListDoesNothing() {
        adapter.addAll(List.of());
        verify(repository, never()).deleteAllByIdInBatch(anyList());
        verify(repository, never()).saveAll(anyList());
    }

    @Test
    void similaritySearchAppliesVisibilityFilterAndCarriesDistance() {
        when(embeddingModel.embed(anyString())).thenReturn(new float[]{0.5f, 0.6f});
        Query query = mock(Query.class);
        when(entityManager.createNativeQuery(anyString())).thenReturn(query);
        when(query.setParameter(anyString(), any())).thenReturn(query);
        Object[] row = new Object[]{
                "doc-1#chunk-0", "命中内容", "标题", "源文件.md", "docs/源文件.md", 0,
                "doc-1", "源文件.md", "hash-1", "标题", 1, 0, 10, "text-paragraph",
                "SOURCE", "1", "ws-1", "PUBLIC", 0, 0.123d};
        when(query.getResultList()).thenReturn(java.util.Collections.singletonList(row));

        List<SourceDocument> results = adapter.similaritySearch("问题", 5, "1", Set.of("ws-1"));

        assertThat(results).hasSize(1);
        assertThat(results.get(0).id()).isEqualTo("doc-1#chunk-0");
        assertThat(results.get(0).score()).isEqualTo(0.123d);
        assertThat(results.get(0).visibility()).isEqualTo(DocumentVisibility.PUBLIC);
        assertThat(results.get(0).documentId()).isEqualTo("doc-1");
    }

    @Test
    void similaritySearchExcludesLearningRecordsInSql() {
        when(embeddingModel.embed(anyString())).thenReturn(new float[]{0.5f});
        Query query = mock(Query.class);
        when(entityManager.createNativeQuery(anyString())).thenReturn(query);
        when(query.setParameter(anyString(), any())).thenReturn(query);
        when(query.getResultList()).thenReturn(List.of());

        adapter.similaritySearch("问题", 5);

        org.mockito.ArgumentCaptor<String> sql = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(entityManager).createNativeQuery(sql.capture());
        assertThat(sql.getValue()).contains("category <> 'LEARNING_RECORD'");
        assertThat(sql.getValue()).contains("embedding OPERATOR(public.<=>) cast(:queryVector as public.vector)");
        assertThat(sql.getValue()).contains("embedding is not null");
    }

    @Test
    void documentsByIdsWithEmptyIdsReturnsEmptyWithoutQuery() {
        assertThat(adapter.documentsByIds(List.of())).isEmpty();
        verify(entityManager, never()).createNativeQuery(anyString());
    }

    @Test
    void refreshVisibilityMetadataUpdatesThreeColumnsPerChunk() {
        Query update = mock(Query.class);
        when(entityManager.createNativeQuery(anyString())).thenReturn(update);
        when(update.setParameter(anyString(), any())).thenReturn(update);

        adapter.refreshVisibilityMetadata(List.of(sampleChunk("doc-1#chunk-0", "内容")));

        verify(update).executeUpdate();
        verify(update).setParameter("visibility", "PUBLIC");
        verify(update).setParameter("ownerUserId", "1");
        verify(update).setParameter("workspaceId", "ws-1");
    }

    @Test
    void throwsWhenEmbeddingModelUnavailable() {
        ObjectProvider<EmbeddingModel> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        PgVectorStoreAdapter broken = new PgVectorStoreAdapter(repository, provider);
        ReflectionTestUtils.setField(broken, "entityManager", entityManager);

        assertThatThrownBy(() -> broken.similaritySearch("问题", 5))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("EmbeddingModel");
    }
}
