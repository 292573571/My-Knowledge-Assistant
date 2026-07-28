package com.example.workbench.rag;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DocumentIndexRepository extends JpaRepository<DocumentIndexEntity, Long> {
    List<DocumentIndexEntity> findAllByOrderByFileNameAsc();
    void deleteByDocumentId(String documentId);
    void deleteByPathOrContentHash(String path, String contentHash);
}
