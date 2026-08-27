package com.example.workbench.rag;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DocumentIndexStoreTest {

    @TempDir
    Path tempDir;

    private DocumentIndexStore store;

    @BeforeEach
    void setUp() {
        store = new DocumentIndexStore(new ObjectMapper(), tempDir.resolve("document-index.json"));
    }

    @Test
    void replaceAllPersistsEntriesSortedByFileName() {
        store.replaceAll(List.of(
                entry("b", "b.md"),
                entry("a", "a.md")
        ));

        assertThat(store.list()).extracting(DocumentIndexEntry::fileName)
                .containsExactly("a.md", "b.md");
    }

    @Test
    void deleteRemovesMatchingDocumentId() {
        store.replaceAll(List.of(entry("a", "a.md"), entry("b", "b.md")));

        store.delete("a");

        assertThat(store.list()).extracting(DocumentIndexEntry::documentId)
                .containsExactly("b");
    }

    @Test
    void upsertAllReplacesEntriesWithSamePathOrHash() {
        store.replaceAll(List.of(
                new DocumentIndexEntry("old-path", "same.md", "docs/same.md", "old-hash", 1, java.time.Instant.ofEpochMilli(100L)),
                new DocumentIndexEntry("old-hash", "old.md", "docs/old.md", "same-hash", 1, java.time.Instant.ofEpochMilli(100L)),
                entry("keep", "keep.md")
        ));

        store.upsertAll(List.of(
                new DocumentIndexEntry("new-path", "same.md", "docs/same.md", "new-hash", 2, java.time.Instant.ofEpochMilli(200L)),
                new DocumentIndexEntry("new-hash", "new.md", "docs/new.md", "same-hash", 3, java.time.Instant.ofEpochMilli(200L))
        ));

        assertThat(store.list()).extracting(DocumentIndexEntry::documentId)
                .containsExactly("keep", "new-hash", "new-path");
    }

    @Test
    void upsertDoesNotRemoveSameDocumentIdentifiersFromAnotherWorkspace() {
        store.replaceAll(List.of(
                new DocumentIndexEntry("same", "team.md", "docs/team.md", "team-hash", 1, java.time.Instant.ofEpochMilli(100L),
                        "SOURCE", "INDEXED", "1"),
                new DocumentIndexEntry("same", "other.md", "docs/other.md", "other-hash", 1, java.time.Instant.ofEpochMilli(100L),
                        "SOURCE", "INDEXED", "2")
        ));

        store.upsertAll(List.of(new DocumentIndexEntry("same", "new.md", "docs/new.md", "new-hash", 1, java.time.Instant.ofEpochMilli(200L),
                "SOURCE", "INDEXED", "1")));

        assertThat(store.list()).extracting(DocumentIndexEntry::fileName)
                .containsExactly("new.md", "other.md");
    }

    @Test
    void exposesIngestedAtAsInstant() {
        Instant ingestedAt = Instant.parse("2026-08-26T10:00:00Z");

        DocumentIndexEntry entry = new DocumentIndexEntry("instant", "instant.md", "docs/instant.md",
                "instant-hash", 1, ingestedAt);

        assertThat(entry.ingestedAt()).isEqualTo(ingestedAt);
    }

    @Test
    void clearDeletesIndexFile() {
        store.replaceAll(List.of(entry("a", "a.md")));

        store.clear();

        assertThat(store.list()).isEmpty();
    }

    private DocumentIndexEntry entry(String documentId, String fileName) {
        return new DocumentIndexEntry(documentId, fileName, "docs/" + fileName, "hash-" + documentId, 1,
                java.time.Instant.ofEpochMilli(123L));
    }
}
