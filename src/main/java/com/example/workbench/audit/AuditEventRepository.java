package com.example.workbench.audit;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditEventRepository extends JpaRepository<AuditEvent, Long> {

    List<AuditEvent> findTop200ByWorkspaceIdOrderByCreatedAtDesc(String workspaceId);
}
