package com.example.workbench.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.workbench.auth.AppUser;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

class JpaConversationContextStoreTest {

    @Test
    void readsOnlyTheAuthorizedWorkspaceHistoryInChronologicalOrder() {
        ChatConversationRepository conversationRepository = mock(ChatConversationRepository.class);
        ChatMessageRepository messageRepository = mock(ChatMessageRepository.class);
        JpaConversationContextStore store = new JpaConversationContextStore(conversationRepository, messageRepository);
        AppUser user = new AppUser("alice", "Alice", "hash");
        ChatConversation conversation = new ChatConversation("server-1", "client-1", user,
                "测试会话", "rag", "workspace-a");
        when(conversationRepository.findVisibleByIdAndUserAndWorkspace(
                "client-1", user.getId(), "workspace-a", "personal-null"))
                .thenReturn(java.util.Optional.of(conversation));
        ChatMessageEntity assistant = new ChatMessageEntity(conversation, "assistant", "较早回答", "[]", "[]");
        ChatMessageEntity question = new ChatMessageEntity(conversation, "user", "较新问题", "[]", "[]");
        when(messageRepository.findByConversationIdOrderByCreatedAtDesc(any(String.class), any(Pageable.class)))
                .thenReturn(List.of(question, assistant));

        List<com.example.workbench.memory.ChatMessage> history = store.recent(user, "workspace-a", "client-1", 2);

        assertThat(history).extracting(com.example.workbench.memory.ChatMessage::content)
                .containsExactly("较早回答", "较新问题");
    }

    @Test
    void returnsNoHistoryWhenTheConversationDoesNotBelongToTheRequestedWorkspace() {
        ChatConversationRepository conversationRepository = mock(ChatConversationRepository.class);
        ChatMessageRepository messageRepository = mock(ChatMessageRepository.class);
        JpaConversationContextStore store = new JpaConversationContextStore(conversationRepository, messageRepository);
        AppUser user = new AppUser("alice", "Alice", "hash");
        when(conversationRepository.findVisibleByIdAndUserAndWorkspace(
                "client-1", user.getId(), "workspace-b", "personal-null"))
                .thenReturn(java.util.Optional.empty());

        assertThat(store.recent(user, "workspace-b", "client-1", 2)).isEmpty();
    }
}
