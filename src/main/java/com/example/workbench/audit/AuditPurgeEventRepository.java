package com.example.workbench.audit;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditPurgeEventRepository extends JpaRepository<AuditPurgeEvent, Long> {
}
