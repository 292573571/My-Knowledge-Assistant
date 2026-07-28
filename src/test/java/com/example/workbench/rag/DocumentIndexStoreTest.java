package com.example.workbench.rag;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
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
                new DocumentIndexEntry("old-path", "same.md", "docs/same.md", "old-hash", 1, 100L),
                new DocumentIndexEntry("old-hash", "old.md", "docs/old.md", "same-hash", 1, 100L),
                entry("keep", "keep.md")
        ));

        store.upsertAll(List.of(
                new DocumentIndexEntry("new-path", "same.md", "docs/same.md", "new-hash", 2, 200L),
                new DocumentIndexEntry("new-hash", "new.md", "docs/new.md", "same-hash", 3, 200L)
        ));

        assertThat(store.list()).extracting(DocumentIndexEntry::documentId)
                .containsExactly("keep", "new-hash", "new-path");
    }

    @Test
    void clearDeletesIndexFile() {
        store.replaceAll(List.of(entry("a", "a.md")));

        store.clear();

        assertThat(store.list()).isEmpty();
    }

    private DocumentIndexEntry entry(String documentId, String fileName) {
        return new DocumentIndexEntry(documentId, fileName, "docs/" + fileName, "hash-" + documentId, 1, 123L);
    }
}
