package com.example.workbench.rag;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class DocumentIndexStore {

    private static final Path DEFAULT_INDEX_PATH = Path.of("data", "document-index.json");

    private final ObjectMapper objectMapper;
    private final Path indexPath;

    @Autowired
    public DocumentIndexStore(ObjectMapper objectMapper) {
        this(objectMapper, DEFAULT_INDEX_PATH);
    }

    DocumentIndexStore(ObjectMapper objectMapper, Path indexPath) {
        this.objectMapper = objectMapper;
        this.indexPath = indexPath;
    }

    public synchronized List<DocumentIndexEntry> list() {
        if (!Files.exists(indexPath)) {
            return List.of();
        }

        try {
            return objectMapper.readValue(indexPath.toFile(), new TypeReference<>() {});
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to read document index", exception);
        }
    }

    public synchronized void replaceAll(List<DocumentIndexEntry> entries) {
        try {
            Files.createDirectories(indexPath.getParent());
            List<DocumentIndexEntry> sortedEntries = new ArrayList<>(entries);
            sortedEntries.sort(Comparator.comparing(DocumentIndexEntry::fileName));
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(indexPath.toFile(), sortedEntries);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to write document index", exception);
        }
    }

    public synchronized void upsertAll(List<DocumentIndexEntry> entries) {
        List<DocumentIndexEntry> nextEntries = new ArrayList<>(list());

        for (DocumentIndexEntry entry : entries) {
            nextEntries.removeIf(existing -> existing.documentId().equals(entry.documentId())
                    || existing.path().equals(entry.path())
                    || existing.contentHash().equals(entry.contentHash()));
            nextEntries.add(entry);
        }

        replaceAll(nextEntries);
    }

    public synchronized void delete(String documentId) {
        replaceAll(list().stream()
                .filter(entry -> !entry.documentId().equals(documentId))
                .toList());
    }

    public synchronized void clear() {
        try {
            Files.deleteIfExists(indexPath);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to clear document index", exception);
        }
    }
}
