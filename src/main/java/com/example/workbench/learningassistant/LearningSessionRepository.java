package com.example.workbench.learningassistant;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LearningSessionRepository extends JpaRepository<LearningSessionEntity, String> {
    List<LearningSessionEntity> findByUserIdAndWorkspaceIdOrderByUpdatedAtDesc(Long userId, String workspaceId);

    Optional<LearningSessionEntity> findBySessionIdAndUserIdAndWorkspaceId(String sessionId, Long userId, String workspaceId);
}
