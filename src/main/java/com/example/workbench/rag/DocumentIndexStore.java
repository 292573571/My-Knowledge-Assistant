package com.example.workbench.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class DocumentIndexStore {

    private final DocumentIndexRepository repository;
    private List<DocumentIndexEntry> testEntries;

    @Autowired
    public DocumentIndexStore(DocumentIndexRepository repository) {
        this.repository = repository;
    }

    // 仅供既有轻量单测使用；生产代码始终通过 repository 读写 PostgreSQL。
    DocumentIndexStore(ObjectMapper objectMapper, Path indexPath) {
        this.repository = null;
        this.testEntries = new ArrayList<>();
    }

    @Transactional(readOnly = true)
    public synchronized List<DocumentIndexEntry> list() {
        if (repository == null) return List.copyOf(testEntries);
        return repository.findAllByOrderByFileNameAsc().stream().map(DocumentIndexEntity::toEntry).toList();
    }

    @Transactional
    public synchronized void replaceAll(List<DocumentIndexEntry> entries) {
        List<DocumentIndexEntry> sortedEntries = new ArrayList<>(entries);
        sortedEntries.sort(Comparator.comparing(DocumentIndexEntry::fileName));
        if (repository == null) {
            testEntries = sortedEntries;
            return;
        }
        repository.deleteAllInBatch();
        repository.saveAll(sortedEntries.stream().map(DocumentIndexEntity::new).toList());
    }

    @Transactional
    public synchronized void upsertAll(List<DocumentIndexEntry> entries) {
        if (repository == null) {
            List<DocumentIndexEntry> nextEntries = new ArrayList<>(list());
            for (DocumentIndexEntry entry : entries) {
                nextEntries.removeIf(existing -> sameWorkspace(existing, entry)
                        && (existing.documentId().equals(entry.documentId()) || existing.path().equals(entry.path())
                        || existing.contentHash().equals(entry.contentHash())));
                nextEntries.add(entry);
            }
            replaceAll(nextEntries);
            return;
        }
        for (DocumentIndexEntry entry : entries) {
            repository.deleteByDocumentIdAndWorkspace(entry.documentId(), entry.workspaceId());
            repository.deleteByPathOrContentHashAndWorkspace(entry.path(), entry.contentHash(), entry.workspaceId());
            repository.save(new DocumentIndexEntity(entry));
        }
    }

    @Transactional
    public synchronized void delete(String documentId) {
        if (repository == null) {
            replaceAll(list().stream().filter(entry -> !entry.documentId().equals(documentId)).toList());
        } else {
            repository.findAllByDocumentId(documentId).forEach(entry ->
                    repository.deleteByDocumentIdAndWorkspace(documentId, entry.toEntry().workspaceId()));
        }
    }

    @Transactional
    public synchronized void delete(String documentId, String workspaceId) {
        if (repository == null) {
            replaceAll(list().stream().filter(entry -> !entry.documentId().equals(documentId)
                    || !sameWorkspace(entry.workspaceId(), workspaceId)).toList());
        } else {
            repository.deleteByDocumentIdAndWorkspace(documentId, workspaceId);
        }
    }

    @Transactional
    public synchronized void clear() {
        if (repository == null) testEntries = new ArrayList<>();
        else repository.deleteAllInBatch();
    }

    private boolean sameWorkspace(DocumentIndexEntry left, DocumentIndexEntry right) {
        return sameWorkspace(left.workspaceId(), right.workspaceId());
    }

    private boolean sameWorkspace(String left, String right) {
        return left == null ? right == null : left.equals(right);
    }
}
