package com.example.workbench.learningassistant;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LearningSessionRepository extends JpaRepository<LearningSessionEntity, String> {
    List<LearningSessionEntity> findByUserIdAndWorkspaceIdOrderByUpdatedAtDescSessionIdDesc(Long userId, String workspaceId);
    Page<LearningSessionEntity> findByUserIdAndWorkspaceId(Long userId, String workspaceId, Pageable pageable);

    Optional<LearningSessionEntity> findBySessionIdAndUserIdAndWorkspaceId(String sessionId, Long userId, String workspaceId);
}
