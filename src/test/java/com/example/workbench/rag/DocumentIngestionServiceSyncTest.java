package com.example.workbench.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

class DocumentIngestionServiceSyncTest {

    @TempDir
    Path tempDir;

    private Path docsDirectory;
    private InMemoryVectorStore vectorStore;
    private DocumentIndexStore indexStore;
    private DocumentIngestionService service;

    @BeforeEach
    void setUp() throws Exception {
        docsDirectory = Files.createDirectories(tempDir.resolve("docs"));
        vectorStore = new InMemoryVectorStore();
        indexStore = new DocumentIndexStore(new ObjectMapper(), tempDir.resolve("document-index.json"));
        DocumentChunkerRouter router = new DocumentChunkerRouter(List.of(
                new MarkdownSmartChunker(),
                new TextParagraphChunker()
        ));
        service = new DocumentIngestionService(vectorStore, indexStore, router, docsDirectory);
    }

    @Test
    void syncAddsUnchangedUpdatesAndDeletesDocumentsIncrementally() throws Exception {
        Path first = docsDirectory.resolve("first.md");
        Path second = docsDirectory.resolve("second.md");
        Files.writeString(first, "# First\n\nSpring AI uses ChatClient for model calls.");
        Files.writeString(second, "# Second\n\nRAG uses embedding and a vector store.");

        SyncResult firstSync = service.syncDocsDirectory();

        assertThat(firstSync.addedFiles()).isEqualTo(2);
        assertThat(firstSync.updatedFiles()).isZero();
        assertThat(firstSync.unchangedFiles()).isZero();
        assertThat(firstSync.deletedFiles()).isZero();
        assertThat(firstSync.addedChunks()).isPositive();
        assertThat(indexStore.list()).hasSize(2);

        SyncResult unchangedSync = service.syncDocsDirectory();

        assertThat(unchangedSync.addedFiles()).isZero();
        assertThat(unchangedSync.updatedFiles()).isZero();
        assertThat(unchangedSync.unchangedFiles()).isEqualTo(2);
        assertThat(unchangedSync.addedChunks()).isZero();

        Files.writeString(first, "# First\n\nSpring AI ChatClient supports token usage and finish reasons.");
        Files.delete(second);

        SyncResult changedSync = service.syncDocsDirectory();

        assertThat(changedSync.scannedFiles()).isEqualTo(1);
        assertThat(changedSync.updatedFiles()).isEqualTo(1);
        assertThat(changedSync.deletedFiles()).isEqualTo(1);
        assertThat(changedSync.addedChunks()).isPositive();
        assertThat(changedSync.deletedChunks()).isPositive();
        assertThat(indexStore.list()).hasSize(1);
        assertThat(vectorStore.similaritySearch("finish reasons", 5))
                .extracting(SourceDocument::fileName)
                .contains("first.md")
                .doesNotContain("second.md");
    }

    @Test
    void deletesPersistedDocumentByChunkIdsWithoutReplacingUnknownVectors() throws Exception {
        Path document = docsDirectory.resolve("persisted.md");
        Files.writeString(document, "# Persisted\n\nContent that was indexed before the process restarted.");
        service.syncDocsDirectory();
        DocumentIndexEntry entry = indexStore.list().get(0);
        VectorStore restartedVectorStore = Mockito.mock(VectorStore.class);
        DocumentIngestionService restartedService = new DocumentIngestionService(
                restartedVectorStore,
                indexStore,
                new DocumentChunkerRouter(List.of(new MarkdownSmartChunker(), new TextParagraphChunker())),
                docsDirectory
        );

        restartedService.deleteDocument(entry.documentId());

        verify(restartedVectorStore).deleteByIds(java.util.stream.IntStream.range(0, entry.chunkCount())
                .mapToObj(index -> entry.documentId() + "#chunk-" + index)
                .toList());
        verify(restartedVectorStore, never()).replaceAll(Mockito.anyList());
        assertThat(indexStore.list()).isEmpty();
    }

    @Test
    void readsAnIndexedDocumentFromTheDocsDirectory() throws Exception {
        Path document = docsDirectory.resolve("readable.md");
        Files.writeString(document, "# Readable\n\nThis is the complete source content.");
        service.syncDocsDirectory();
        DocumentIndexEntry entry = indexStore.list().get(0);

        DocumentContentResponse response = service.documentContent(entry.documentId(), "");

        assertThat(response.fileName()).isEqualTo("readable.md");
        assertThat(response.content()).contains("complete source content");
    }

    @Test
    void rejectsReadingAPrivateDocumentOwnedByAnotherUser() throws Exception {
        Path directory = Files.createDirectories(docsDirectory.resolve("manual-notes/user-2"));
        Files.writeString(directory.resolve("2026-07-27.md"), "# Private\n\nOnly user 2 can read this note.");
        service.syncDocsDirectory();
        DocumentIndexEntry entry = indexStore.list().get(0);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.documentContent(entry.documentId(), "1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Indexed document not found");
    }
}
