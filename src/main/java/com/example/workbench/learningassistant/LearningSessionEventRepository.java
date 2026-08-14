package com.example.workbench.learningassistant;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LearningSessionEventRepository extends JpaRepository<LearningSessionEventEntity, String> {
    Optional<LearningSessionEventEntity> findBySessionIdAndClientRequestId(String sessionId, String clientRequestId);
}
