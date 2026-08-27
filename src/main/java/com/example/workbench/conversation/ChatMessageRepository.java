package com.example.workbench.conversation;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.Page;

public interface ChatMessageRepository extends JpaRepository<ChatMessageEntity, Long> {
    List<ChatMessageEntity> findByConversationIdOrderByCreatedAtAsc(String conversationId);
    Page<ChatMessageEntity> findByConversationId(String conversationId, Pageable pageable);
    List<ChatMessageEntity> findByConversationIdOrderByCreatedAtDesc(String conversationId, Pageable pageable);
    void deleteByConversationId(String conversationId);
    Optional<ChatMessageEntity> findByConversationIdAndClientRequestIdAndRole(
            String conversationId, String clientRequestId, String role);

    @Modifying
    @Transactional
    @Query(value = """
            insert into chat_messages (conversation_id, role, content, sources_json, tool_calls_json,
                                       client_request_id, created_at)
            values (:conversationId, :role, :content, :sourcesJson, :toolCallsJson, :clientRequestId, CURRENT_TIMESTAMP)
            on conflict (conversation_id, client_request_id, role) do nothing
            """, nativeQuery = true)
    int insertIfAbsent(@Param("conversationId") String conversationId, @Param("role") String role,
                       @Param("content") String content, @Param("sourcesJson") String sourcesJson,
                       @Param("toolCallsJson") String toolCallsJson, @Param("clientRequestId") String clientRequestId);
}
