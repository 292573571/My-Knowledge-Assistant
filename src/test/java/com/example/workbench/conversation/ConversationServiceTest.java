package com.example.workbench.conversation;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.workbench.auth.AppUser;
import com.example.workbench.auth.UserConversationScope;
import com.example.workbench.memory.ConversationMemory;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.server.ResponseStatusException;

class ConversationServiceTest {

    @Test
    void marksTheProductionConstructorForSpringInjectionWhenTestConstructorAlsoExists() {
        assertThat(ConversationService.class.getConstructors())
                .anySatisfy(constructor -> assertThat(constructor.isAnnotationPresent(Autowired.class)).isTrue());
    }

    @Test
    void createsScopedInternalIdWhenAnotherUserUsesSameClientConversationId() {
        ChatConversationRepository conversations = Mockito.mock(ChatConversationRepository.class);
        ChatMessageRepository messages = Mockito.mock(ChatMessageRepository.class);
        ConversationService service = new ConversationService(conversations, messages, new ConversationMemory(),
                new ConversationExecutionRegistry(), new ObjectMapper());
        AppUser owner = new AppUser("alice", "Alice", "hash");
        AppUser anotherUser = new AppUser("bob", "Bob", "hash");
        ChatConversation existing = new ChatConversation("default", owner, "已有会话", "rag", "team-1");
        ConversationRequest request = new ConversationRequest("default", "新会话", "rag");

        when(conversations.findVisibleByIdAndUserAndWorkspace("default", anotherUser.getId(), "team-1", "personal-null"))
                .thenReturn(Optional.empty());
        when(conversations.save(Mockito.any())).thenAnswer(invocation -> invocation.getArgument(0));

        ConversationResponse response = service.create(anotherUser, "team-1", request);

        assertThat(response.id()).isEqualTo("default");
        verify(conversations).save(Mockito.argThat(conversation -> !conversation.getId().equals(existing.getId())
                && conversation.getClientConversationId().equals("default")
                && conversation.getUser() == anotherUser));
    }

    @Test
    void createsScopedInternalIdWhenSameUserUsesClientIdInAnotherWorkspace() {
        ChatConversationRepository conversations = Mockito.mock(ChatConversationRepository.class);
        ChatMessageRepository messages = Mockito.mock(ChatMessageRepository.class);
        ConversationService service = new ConversationService(conversations, messages, new ConversationMemory(),
                new ConversationExecutionRegistry(), new ObjectMapper());
        AppUser user = new AppUser("alice", "Alice", "hash");
        ChatConversation existing = new ChatConversation("default", user, "已有会话", "rag", "team-1");

        when(conversations.findVisibleByIdAndUserAndWorkspace("default", user.getId(), "team-2", "personal-null"))
                .thenReturn(Optional.empty());
        when(conversations.save(Mockito.any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.recordUserMessage(user, "team-2", "default", "新会话", "rag", "你好");

        verify(conversations).save(Mockito.argThat(conversation -> !conversation.getId().equals(existing.getId())
                && conversation.getClientConversationId().equals("default")
                && conversation.getWorkspaceId().equals("team-2")));
        verify(messages).save(Mockito.any(ChatMessageEntity.class));
    }

    @Test
    void doesNotReadMessagesForConversationNotOwnedByCurrentUser() {
        ChatConversationRepository conversations = Mockito.mock(ChatConversationRepository.class);
        ChatMessageRepository messages = Mockito.mock(ChatMessageRepository.class);
        ConversationService service = new ConversationService(conversations, messages, new ConversationMemory(), new ConversationExecutionRegistry(), new ObjectMapper());
        AppUser anotherUser = new AppUser("bob", "Bob", "hash");

        when(conversations.findVisibleByIdAndUserAndWorkspace("conversation-a", anotherUser.getId(), "team-1", "personal-null"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.messages(anotherUser, "team-1", "conversation-a"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404 NOT_FOUND");

        verify(messages, never()).findByConversationIdOrderByCreatedAtAsc("conversation-a");
    }

    @Test
    void readsMessagesUsingServerConversationIdForClientConversationId() {
        ChatConversationRepository conversations = Mockito.mock(ChatConversationRepository.class);
        ChatMessageRepository messages = Mockito.mock(ChatMessageRepository.class);
        ConversationService service = new ConversationService(conversations, messages, new ConversationMemory(),
                new ConversationExecutionRegistry(), new ObjectMapper());
        AppUser user = new AppUser("alice", "Alice", "hash");
        ChatConversation conversation = new ChatConversation("server-uuid", "default", user, "测试", "rag", "team-1");

        when(conversations.findVisibleByIdAndUserAndWorkspace("default", user.getId(), "team-1", "personal-null"))
                .thenReturn(Optional.of(conversation));
        when(messages.findByConversationIdOrderByCreatedAtAsc("server-uuid")).thenReturn(List.of());

        assertThat(service.messages(user, "team-1", "default")).isEmpty();
        verify(messages).findByConversationIdOrderByCreatedAtAsc("server-uuid");
    }

    @Test
    void doesNotRecreateDeletedConversationForAssistantResult() {
        ChatConversationRepository conversations = Mockito.mock(ChatConversationRepository.class);
        ChatMessageRepository messages = Mockito.mock(ChatMessageRepository.class);
        ConversationService service = new ConversationService(conversations, messages, new ConversationMemory(), new ConversationExecutionRegistry(), new ObjectMapper());
        AppUser user = new AppUser("alice", "Alice", "hash");

        when(conversations.findVisibleByIdAndUserAndWorkspace("conversation-a", user.getId(), "team-1", "personal-null"))
                .thenReturn(Optional.empty());

        boolean recorded = service.recordAssistantMessage(user, "team-1", "conversation-a", "rag", "迟到的回答",
                java.util.List.of(), java.util.List.of());

        assertThat(recorded).isFalse();
        verify(conversations, never()).save(Mockito.any(ChatConversation.class));
        verify(messages, never()).save(Mockito.any(ChatMessageEntity.class));
    }

    @Test
    void recordsOnlyOneUserAndAssistantMessageForTheSameClientRequest() {
        ChatConversationRepository conversations = Mockito.mock(ChatConversationRepository.class);
        ChatMessageRepository messages = Mockito.mock(ChatMessageRepository.class);
        ConversationService service = new ConversationService(conversations, messages, new ConversationMemory(),
                new ConversationExecutionRegistry(), new ObjectMapper());
        AppUser user = new AppUser("alice", "Alice", "hash");
        ChatConversation conversation = new ChatConversation("conversation-a", user, "测试", "rag", "team-1");
        when(conversations.findVisibleByIdAndUserAndWorkspace("conversation-a", user.getId(), "team-1", "personal-null"))
                .thenReturn(Optional.of(conversation));
        when(conversations.save(Mockito.any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(messages.findByConversationIdAndClientRequestIdAndRole("conversation-a", "request-1", "user"))
                .thenReturn(Optional.of(Mockito.mock(ChatMessageEntity.class)));
        when(messages.findByConversationIdAndClientRequestIdAndRole("conversation-a", "request-1", "assistant"))
                .thenReturn(Optional.of(Mockito.mock(ChatMessageEntity.class)));

        service.recordUserMessage(user, "team-1", "conversation-a", "request-1", "测试", "rag", "问题");
        boolean recorded = service.recordAssistantMessage(user, "team-1", "conversation-a", "request-1",
                "rag", "答案", List.of(), List.of());

        assertThat(recorded).isTrue();
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

        when(conversations.findVisibleByIdAndUserAndWorkspace("conversation-a", user.getId(), "team-1", "personal-null"))
                .thenReturn(Optional.of(conversation));
        ConversationExecutionRegistry.Execution execution = executions.begin(service.executionScope(user, "team-1", "conversation-a"));

        service.delete(user, "team-1", "conversation-a");

        assertThat(execution.isCancelled()).isTrue();
        verify(messages).deleteByConversationId("conversation-a");
        verify(conversations).deleteById("conversation-a");
    }

    @Test
    void doesNotReadConversationFromAnotherWorkspaceOwnedBySameUser() {
        ChatConversationRepository conversations = Mockito.mock(ChatConversationRepository.class);
        ChatMessageRepository messages = Mockito.mock(ChatMessageRepository.class);
        ConversationService service = new ConversationService(conversations, messages, new ConversationMemory(),
                new ConversationExecutionRegistry(), new ObjectMapper());
        AppUser user = new AppUser("alice", "Alice", "hash");
        when(conversations.findVisibleByIdAndUserAndWorkspace("conversation-a", user.getId(), "team-1", "personal-null"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.messages(user, "team-1", "conversation-a"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404 NOT_FOUND");
        verify(messages, never()).findByConversationIdOrderByCreatedAtAsc("conversation-a");
    }
}
