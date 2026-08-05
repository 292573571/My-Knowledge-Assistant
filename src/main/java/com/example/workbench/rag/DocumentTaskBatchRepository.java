package com.example.workbench.rag;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

interface DocumentTaskBatchRepository extends JpaRepository<DocumentTaskBatchEntity, String> {
    List<DocumentTaskBatchEntity> findByTaskIdOrderByBatchIndex(String taskId);
    void deleteByTaskId(String taskId);
}
