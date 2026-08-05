package com.example.workbench.rag;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

interface DocumentTaskBatchArtifactRepository extends JpaRepository<DocumentTaskBatchArtifactEntity, String> {
    List<DocumentTaskBatchArtifactEntity> findByTaskIdOrderByBatchIndex(String taskId);
    void deleteByTaskId(String taskId);
}
