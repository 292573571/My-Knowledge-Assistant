package com.example.workbench.conversation;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Pageable;

public interface ChatMessageRepository extends JpaRepository<ChatMessageEntity, Long> {
    List<ChatMessageEntity> findByConversationIdOrderByCreatedAtAsc(String conversationId);
    List<ChatMessageEntity> findByConversationIdOrderByCreatedAtDesc(String conversationId, Pageable pageable);
    void deleteByConversationId(String conversationId);
}
