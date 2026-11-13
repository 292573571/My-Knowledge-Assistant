package com.example.workbench.conversation;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChatFeedbackRepository extends JpaRepository<ChatFeedbackEntity, Long> {

    Optional<ChatFeedbackEntity> findByMessageIdAndUserId(Long messageId, Long userId);

    List<ChatFeedbackEntity> findTop200ByWorkspaceIdOrderByUpdatedAtDesc(String workspaceId);

    boolean existsByMessageIdAndUserId(Long messageId, Long userId);
}
