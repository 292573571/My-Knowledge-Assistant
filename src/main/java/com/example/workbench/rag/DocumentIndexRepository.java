package com.example.workbench.rag;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface DocumentIndexRepository extends JpaRepository<DocumentIndexEntity, Long> {
    List<DocumentIndexEntity> findAllByOrderByFileNameAsc();
    List<DocumentIndexEntity> findAllByDocumentId(String documentId);

    @Modifying
    @Transactional
    @Query("delete from DocumentIndexEntity item where item.documentId = :documentId "
            + "and ((item.workspaceId = :workspaceId) or (:workspaceId is null and item.workspaceId is null))")
    void deleteByDocumentIdAndWorkspace(@Param("documentId") String documentId, @Param("workspaceId") String workspaceId);

    @Modifying
    @Transactional
    @Query("delete from DocumentIndexEntity item where (item.path = :path or item.contentHash = :contentHash) "
            + "and ((item.workspaceId = :workspaceId) or (:workspaceId is null and item.workspaceId is null))")
    void deleteByPathOrContentHashAndWorkspace(@Param("path") String path, @Param("contentHash") String contentHash,
                                               @Param("workspaceId") String workspaceId);
}
