package com.example.workbench.rag;

import org.springframework.data.jpa.repository.JpaRepository;

public interface RagQualityAuditRepository extends JpaRepository<RagQualityAuditEntity, Long> {
}
