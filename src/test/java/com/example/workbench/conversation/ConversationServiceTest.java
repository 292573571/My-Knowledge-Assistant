package com.example.workbench.conversation;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.workbench.auth.AppUser;
import com.example.workbench.memory.ConversationMemory;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.web.server.ResponseStatusException;

class ConversationServiceTest {

    @Test
    void doesNotReadMessagesForConversationNotOwnedByCurrentUser() {
        ChatConversationRepository conversations = Mockito.mock(ChatConversationRepository.class);
        ChatMessageRepository messages = Mockito.mock(ChatMessageRepository.class);
        ConversationService service = new ConversationService(conversations, messages, new ConversationMemory(), new ConversationExecutionRegistry(), new ObjectMapper());
        AppUser anotherUser = new AppUser("bob", "Bob", "hash");

        when(conversations.findByIdAndUserId("conversation-a", anotherUser.getId())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.messages(anotherUser, "conversation-a"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404 NOT_FOUND");

        verify(messages, never()).findByConversationIdOrderByCreatedAtAsc("conversation-a");
    }

    @Test
    void doesNotRecreateDeletedConversationForAssistantResult() {
        ChatConversationRepository conversations = Mockito.mock(ChatConversationRepository.class);
        ChatMessageRepository messages = Mockito.mock(ChatMessageRepository.class);
        ConversationService service = new ConversationService(conversations, messages, new ConversationMemory(), new ConversationExecutionRegistry(), new ObjectMapper());
        AppUser user = new AppUser("alice", "Alice", "hash");

        when(conversations.findByIdAndUserId("conversation-a", user.getId())).thenReturn(Optional.empty());

        boolean recorded = service.recordAssistantMessage(user, "conversation-a", "rag", "迟到的回答", java.util.List.of(), java.util.List.of());

        assertThat(recorded).isFalse();
        verify(conversations, never()).save(Mockito.any(ChatConversation.class));
        verify(messages, never()).save(Mockito.any(ChatMessageEntity.class));
    }

    @Test
    void cancelsGenerationAndDeletesConversationImmediately() {
        ChatConversationRepository conversations = Mockito.mock(ChatConversationRepository.class);
        ChatMessageRepository messages = Mockito.mock(ChatMessageRepository.class);
        ConversationMemory memory = new ConversationMemory();
        ConversationExecutionRegistry executions = new ConversationExecutionRegistry();
        ConversationService service = new ConversationService(conversations, messages, memory, executions, new ObjectMapper());
        AppUser user = new AppUser("alice", "Alice", "hash");
        ChatConversation conversation = new ChatConversation("conversation-a", user, "测试", "rag");

        when(conversations.findByIdAndUserId("conversation-a", user.getId())).thenReturn(Optional.of(conversation));
        ConversationExecutionRegistry.Execution execution = executions.begin("user-" + user.getId() + ":conversation-a");

        service.delete(user, "conversation-a");

        assertThat(execution.isCancelled()).isTrue();
        verify(messages).deleteByConversationId("conversation-a");
        verify(conversations).deleteById("conversation-a");
    }
}
