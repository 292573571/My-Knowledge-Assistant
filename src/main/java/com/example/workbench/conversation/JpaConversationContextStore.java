package com.example.workbench.conversation;

import com.example.workbench.auth.AppUser;
import com.example.workbench.memory.ChatMessage;
import java.util.List;
import java.util.ArrayList;
import java.util.Collections;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.PageRequest;

@Component
class JpaConversationContextStore implements ConversationContextStore {

    private final ChatConversationRepository conversationRepository;
    private final ChatMessageRepository messageRepository;

    JpaConversationContextStore(ChatConversationRepository conversationRepository,
                                 ChatMessageRepository messageRepository) {
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public List<ChatMessage> recent(AppUser user, String workspaceId, String clientConversationId, int maxRounds) {
        ChatConversation conversation = conversationRepository.findVisibleByIdAndUserAndWorkspace(
                        clientConversationId, user.getId(), workspaceId, "personal-" + user.getId())
                .orElse(null);
        if (conversation == null) return List.of();

        int limit = Math.max(0, maxRounds) * 2;
        if (limit == 0) return List.of();
        List<ChatMessageEntity> messages = messageRepository.findByConversationIdOrderByCreatedAtDesc(
                conversation.getId(), PageRequest.of(0, limit));
        List<ChatMessage> result = messages.stream()
                .map(message -> new ChatMessage(message.getRole(), message.getContent()))
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        Collections.reverse(result);
        return List.copyOf(result);
    }
}
