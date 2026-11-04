package com.example.workbench.rag;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface DocumentChunkRepository extends JpaRepository<DocumentChunkEntity, String> {

    void deleteByDocumentId(String documentId);

    List<DocumentChunkEntity> findByDocumentIdAndChunkIndexBetweenOrderByChunkIndex(
            String documentId, int firstChunkIndex, int lastChunkIndex);

    List<DocumentChunkEntity> findByDocumentIdAndWorkspaceIdOrderByChunkIndex(
            String documentId, String workspaceId);

    @Query("select c.documentId, count(c) from DocumentChunkEntity c group by c.documentId")
    List<Object[]> countGroupedByDocumentId();
}
