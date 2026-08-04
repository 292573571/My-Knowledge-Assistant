package com.example.workbench.rag;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

interface DocumentChunkRepository extends JpaRepository<DocumentChunkEntity, String> {

    void deleteByDocumentId(String documentId);

    List<DocumentChunkEntity> findByDocumentIdAndChunkIndexBetweenOrderByChunkIndex(
            String documentId, int firstChunkIndex, int lastChunkIndex);
}
