package com.example.workbench.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.workbench.workspace.DocumentVisibility;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DocumentHealthServiceTest {

    @TempDir
    Path docsDir;

    private DocumentIndexRepository indexRepository;
    private DocumentChunkRepository chunkRepository;
    private VectorStore vectorStore;
    private DocumentIndexStore indexStore;
    private DocumentHealthService service;

    @BeforeEach
    void setUp() {
        indexRepository = mock(DocumentIndexRepository.class);
        chunkRepository = mock(DocumentChunkRepository.class);
        vectorStore = mock(VectorStore.class);
        indexStore = mock(DocumentIndexStore.class);
        service = new DocumentHealthService(indexRepository, chunkRepository,
                new IngestionPathResolver(docsDir), vectorStore, indexStore);
    }

    private DocumentIndexEntry entryOf(String documentId, String fileName, String hash, String workspaceId,
                                       int chunkCount, String status, String category) {
        return entryOf(documentId, fileName, hash, workspaceId, chunkCount, status, category, Instant.now());
    }

    private DocumentIndexEntry entryOf(String documentId, String fileName, String hash, String workspaceId,
                                       int chunkCount, String status, String category, Instant ingestedAt) {
        return new DocumentIndexEntry(documentId, fileName, "docs/workspaces/" + workspaceId + "/" + fileName,
                hash, chunkCount, ingestedAt, category, status, "u1", workspaceId, DocumentVisibility.WORKSPACE);
    }

    private void givenDocuments(DocumentIndexEntry... entries) {
        List<DocumentIndexEntity> entities = java.util.Arrays.stream(entries)
                .map(DocumentIndexEntity::new)
                .toList();
        when(indexRepository.findAllByOrderByFileNameAsc()).thenReturn(entities);
    }

    @Test
    void detectsDuplicatesAcrossWorkspaces() {
        givenDocuments(
                entryOf("doc-1", "a.pdf", "hash-1", "ws-1", 3, "INDEXED", "SOURCE"),
                entryOf("doc-2", "a.pdf", "hash-1", "ws-2", 3, "INDEXED", "SOURCE"),
                entryOf("doc-3", "b.pdf", "hash-2", "ws-1", 2, "INDEXED", "SOURCE"));
        when(chunkRepository.countGroupedByDocumentId()).thenReturn(List.<Object[]>of(
                new Object[]{"doc-1", 3L}, new Object[]{"doc-2", 3L}, new Object[]{"doc-3", 2L}));

        DocumentHealthService.DocumentHealthResponse report = service.report();

        assertThat(report.totalDocuments()).isEqualTo(3);
        assertThat(report.totalChunks()).isEqualTo(8);
        assertThat(report.duplicates()).hasSize(1);
        assertThat(report.duplicates().get(0).contentHash()).isEqualTo("hash-1");
        assertThat(report.duplicates().get(0).count()).isEqualTo(2);
        assertThat(report.duplicates().get(0).documents())
                .extracting(DocumentHealthService.DocumentRef::workspaceId)
                .containsExactlyInAnyOrder("ws-1", "ws-2");
        assertThat(report.orphanIndexes()).isEmpty();
    }

    @Test
    void detectsOrphanIndexesWhenChunkRowsMissing() {
        givenDocuments(
                entryOf("doc-1", "a.pdf", "hash-1", "ws-1", 3, "INDEXED", "SOURCE"),
                entryOf("doc-2", "b.pdf", "hash-2", "ws-1", 2, "INDEXED", "SOURCE"));
        when(chunkRepository.countGroupedByDocumentId()).thenReturn(List.<Object[]>of(new Object[]{"doc-2", 2L}));

        DocumentHealthService.DocumentHealthResponse report = service.report();

        assertThat(report.orphanIndexes()).hasSize(1);
        assertThat(report.orphanIndexes().get(0).documentId()).isEqualTo("doc-1");
        assertThat(report.orphanIndexes().get(0).detail()).contains("分块表无记录");
        assertThat(report.totalChunks()).isEqualTo(2);
    }

    @Test
    void detectsMissingSourceFiles() throws Exception {
        Path existing = docsDir.resolve("workspaces/ws-1/kept.pdf");
        Files.createDirectories(existing.getParent());
        Files.writeString(existing, "content");
        givenDocuments(
                entryOf("doc-1", "kept.pdf", "hash-1", "ws-1", 1, "INDEXED", "SOURCE"),
                entryOf("doc-2", "gone.pdf", "hash-2", "ws-1", 1, "INDEXED", "SOURCE"));
        when(chunkRepository.countGroupedByDocumentId()).thenReturn(List.<Object[]>of(
                new Object[]{"doc-1", 1L}, new Object[]{"doc-2", 1L}));

        DocumentHealthService.DocumentHealthResponse report = service.report();

        assertThat(report.missingSources()).hasSize(1);
        assertThat(report.missingSources().get(0).documentId()).isEqualTo("doc-2");
        assertThat(report.missingSources().get(0).detail()).contains("源文件不存在");
    }

    @Test
    void aggregatesStatusAndCategoryCounts() {
        givenDocuments(
                entryOf("doc-1", "a.pdf", "hash-1", "ws-1", 1, "INDEXED", "SOURCE"),
                entryOf("doc-2", "note.md", "hash-2", "ws-1", 1, "INDEXED", "FORMAL_NOTE"),
                entryOf("doc-3", "wiki.md", "hash-3", "ws-2", 1, "INDEXED", "WIKI_PAGE"));
        when(chunkRepository.countGroupedByDocumentId()).thenReturn(List.<Object[]>of(
                new Object[]{"doc-1", 1L}, new Object[]{"doc-2", 1L}, new Object[]{"doc-3", 1L}));

        DocumentHealthService.DocumentHealthResponse report = service.report();

        assertThat(report.byStatus()).containsEntry("INDEXED", 3L);
        assertThat(report.byCategory()).containsEntry("SOURCE", 1L)
                .containsEntry("FORMAL_NOTE", 1L)
                .containsEntry("WIKI_PAGE", 1L);
        assertThat(report.duplicates()).isEmpty();
    }

    @Test
    void orphanCleanupPlanPreviewsWithoutDeleting() {
        givenDocuments(
                entryOf("doc-1", "a.pdf", "hash-1", "ws-1", 3, "INDEXED", "SOURCE"),
                entryOf("doc-2", "b.pdf", "hash-2", "ws-1", 2, "INDEXED", "SOURCE"));
        when(chunkRepository.countGroupedByDocumentId()).thenReturn(List.<Object[]>of(new Object[]{"doc-2", 2L}));

        DocumentHealthService.CleanupResult plan = service.planCleanup("orphan");

        assertThat(plan.dryRun()).isTrue();
        assertThat(plan.removedCount()).isEqualTo(1);
        assertThat(plan.removed()).hasSize(1);
        assertThat(plan.removed().get(0).documentId()).isEqualTo("doc-1");
        verifyNoInteractions(vectorStore, indexStore);
    }

    @Test
    void cleanupOrphansPurgesVectorIndexAndChunkRows() {
        givenDocuments(
                entryOf("doc-1", "a.pdf", "hash-1", "ws-1", 3, "INDEXED", "SOURCE"),
                entryOf("doc-2", "b.pdf", "hash-2", "ws-1", 2, "INDEXED", "SOURCE"));
        when(chunkRepository.countGroupedByDocumentId()).thenReturn(List.<Object[]>of(new Object[]{"doc-2", 2L}));

        DocumentHealthService.CleanupResult result = service.cleanupOrphans();

        assertThat(result.dryRun()).isFalse();
        assertThat(result.removedCount()).isEqualTo(1);
        assertThat(result.removed().get(0).documentId()).isEqualTo("doc-1");

        verify(vectorStore).deleteByIds(argThat((List<String> ids) -> ids != null && ids.containsAll(
                List.of("doc-1#chunk-0", "doc-1#chunk-1", "doc-1#chunk-2")) && ids.size() == 3));
        verify(indexStore).delete("doc-1", "ws-1");
        verify(chunkRepository).deleteByDocumentId("doc-1");
        // 正常文档完全不受影响。
        verify(indexStore, never()).delete("doc-2", "ws-1");
    }

    @Test
    void cleanupOrphansIgnoresHealthyKnowledgeBase() {
        givenDocuments(entryOf("doc-1", "a.pdf", "hash-1", "ws-1", 2, "INDEXED", "SOURCE"));
        when(chunkRepository.countGroupedByDocumentId()).thenReturn(List.<Object[]>of(new Object[]{"doc-1", 2L}));

        DocumentHealthService.CleanupResult result = service.cleanupOrphans();

        assertThat(result.removedCount()).isZero();
        assertThat(result.removed()).isEmpty();
        verify(vectorStore, never()).deleteByIds(anyList());
        verifyNoInteractions(indexStore);
    }

    @Test
    void cleanupDuplicatesKeepsMostRecentlyIngested() {
        // 重建索引后的新版（doc-2，分块更少但入库更晚）应当保留；旧版 doc-1 虽然分块更多但解析更脏，删除。
        Instant old = Instant.parse("2026-10-01T00:00:00Z");
        Instant fresh = Instant.parse("2026-11-06T00:00:00Z");
        givenDocuments(
                entryOf("doc-1", "a.pdf", "hash-1", "ws-1", 15, "INDEXED", "SOURCE", old),
                entryOf("doc-2", "a-copy.pdf", "hash-1", "ws-2", 12, "INDEXED", "SOURCE", fresh),
                entryOf("doc-3", "b.pdf", "hash-2", "ws-1", 2, "INDEXED", "SOURCE", old));
        when(chunkRepository.countGroupedByDocumentId()).thenReturn(List.<Object[]>of(
                new Object[]{"doc-1", 15L}, new Object[]{"doc-2", 12L}, new Object[]{"doc-3", 2L}));

        DocumentHealthService.CleanupResult result = service.cleanupDuplicates();

        assertThat(result.scope()).isEqualTo("duplicate");
        assertThat(result.removedCount()).isEqualTo(1);
        assertThat(result.removed().get(0).documentId()).isEqualTo("doc-1");
        assertThat(result.kept()).hasSize(1);
        assertThat(result.kept().get(0).documentId()).isEqualTo("doc-2");
        // 内容不同的 doc-3 完全不受影响。
        verify(indexStore).delete("doc-1", "ws-1");
        verify(indexStore, never()).delete("doc-2", "ws-2");
        verify(indexStore, never()).delete("doc-3", "ws-1");
    }

    @Test
    void duplicateCleanupPlanKeepsFirstOnEqualIngestTimeAndChunkCount() {
        // 入库时间与分块数都并列时，保留 fileName 升序的第一份（doc-1），删除 doc-2。
        Instant same = Instant.parse("2026-11-06T00:00:00Z");
        givenDocuments(
                entryOf("doc-1", "a.pdf", "hash-1", "ws-1", 3, "INDEXED", "SOURCE", same),
                entryOf("doc-2", "a-copy.pdf", "hash-1", "ws-2", 3, "INDEXED", "SOURCE", same));
        when(chunkRepository.countGroupedByDocumentId()).thenReturn(List.<Object[]>of(
                new Object[]{"doc-1", 3L}, new Object[]{"doc-2", 3L}));

        DocumentHealthService.CleanupResult plan = service.planCleanup("duplicate");

        assertThat(plan.dryRun()).isTrue();
        assertThat(plan.removed()).extracting(DocumentHealthService.DocumentRef::documentId)
                .containsExactly("doc-2");
        assertThat(plan.kept()).extracting(DocumentHealthService.DocumentRef::documentId)
                .containsExactly("doc-1");
        verifyNoInteractions(vectorStore, indexStore);
    }

    @Test
    void duplicateCleanupPlanFallsBackToChunkCountOnEqualIngestTime() {
        // 入库时间相同（同一批导入）时，分块更全的 doc-2 胜出。
        Instant same = Instant.parse("2026-11-06T00:00:00Z");
        givenDocuments(
                entryOf("doc-1", "a.pdf", "hash-1", "ws-1", 2, "INDEXED", "SOURCE", same),
                entryOf("doc-2", "a-copy.pdf", "hash-1", "ws-2", 5, "INDEXED", "SOURCE", same));
        when(chunkRepository.countGroupedByDocumentId()).thenReturn(List.<Object[]>of(
                new Object[]{"doc-1", 2L}, new Object[]{"doc-2", 5L}));

        DocumentHealthService.CleanupResult plan = service.planCleanup("duplicate");

        assertThat(plan.kept()).extracting(DocumentHealthService.DocumentRef::documentId)
                .containsExactly("doc-2");
        assertThat(plan.removed()).extracting(DocumentHealthService.DocumentRef::documentId)
                .containsExactly("doc-1");
    }
}
