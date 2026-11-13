package com.example.workbench.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.example.workbench.auth.AppUser;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.web.server.ResponseStatusException;

class ChatFeedbackServiceTest {

    private final ChatFeedbackRepository feedbackRepository = Mockito.mock(ChatFeedbackRepository.class);
    private final ConversationService conversationService = Mockito.mock(ConversationService.class);
    private final ChatMessageRepository messageRepository = Mockito.mock(ChatMessageRepository.class);
    private final ChatFeedbackService service = new ChatFeedbackService(feedbackRepository, conversationService,
            messageRepository, new ObjectMapper());

    private final AppUser user = new AppUser("alice", "Alice", "hash");

    @Test
    void submitsFeedbackForOwnedAssistantMessage() {
        ChatConversation conversation = new ChatConversation("server-uuid", "default", user, "测试", "rag", "team-1");
        when(conversationService.requireOwnedConversation(user, "team-1", "default")).thenReturn(conversation);
        ChatMessageEntity message = Mockito.mock(ChatMessageEntity.class);
        when(message.getConversation()).thenReturn(conversation);
        when(message.getRole()).thenReturn("assistant");
        when(messageRepository.findById(457L)).thenReturn(Optional.of(message));
        when(feedbackRepository.findByMessageIdAndUserId(457L, user.getId())).thenReturn(Optional.empty());
        when(feedbackRepository.save(any(ChatFeedbackEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ChatFeedbackService.FeedbackResponse response = service.submit(user, "team-1", "default", 457L, -1, "答非所问");

        assertThat(response.messageId()).isEqualTo(457L);
        assertThat(response.rating()).isEqualTo(-1);
        assertThat(response.reason()).isEqualTo("答非所问");
    }

    @Test
    void rejectsFeedbackOnUserMessage() {
        ChatConversation conversation = new ChatConversation("server-uuid", "default", user, "测试", "rag", "team-1");
        when(conversationService.requireOwnedConversation(user, "team-1", "default")).thenReturn(conversation);
        ChatMessageEntity message = Mockito.mock(ChatMessageEntity.class);
        when(message.getConversation()).thenReturn(conversation);
        when(message.getRole()).thenReturn("user");
        when(messageRepository.findById(1L)).thenReturn(Optional.of(message));

        assertThatThrownBy(() -> service.submit(user, "team-1", "default", 1L, 1, null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("只能对 AI 回答反馈");
    }

    @Test
    void rejectsInvalidRating() {
        assertThatThrownBy(() -> service.submit(user, "team-1", "default", 1L, 0, null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("rating 只能为 1 或 -1");
    }

    @Test
    void buildsAdminItemWithQuestionAndSourceFiles() {
        ChatConversation conversation = new ChatConversation("server-uuid", user, "测试", "rag", "team-1");
        ChatFeedbackEntity feedback = new ChatFeedbackEntity("server-uuid", 457L, user.getId(), "team-1", -1, "太浅");
        when(feedbackRepository.findTop200ByWorkspaceIdOrderByUpdatedAtDesc("team-1")).thenReturn(List.of(feedback));
        ChatMessageEntity answer = Mockito.mock(ChatMessageEntity.class);
        when(answer.getConversation()).thenReturn(conversation);
        when(answer.getClientRequestId()).thenReturn("req-1");
        when(answer.getContent()).thenReturn("回答正文");
        when(answer.getSourcesJson()).thenReturn("[{\"file\":\"deploy-guide.md\"}]");
        when(messageRepository.findById(457L)).thenReturn(Optional.of(answer));
        ChatMessageEntity question = Mockito.mock(ChatMessageEntity.class);
        when(question.getContent()).thenReturn("如何部署？");
        when(messageRepository.findByConversationIdAndClientRequestIdAndRole("server-uuid", "req-1", "user"))
                .thenReturn(Optional.of(question));

        List<ChatFeedbackService.AdminFeedbackItem> items = service.listForAdmin("team-1", -1);

        assertThat(items).hasSize(1);
        assertThat(items.get(0).question()).isEqualTo("如何部署？");
        assertThat(items.get(0).answer()).isEqualTo("回答正文");
        assertThat(items.get(0).sourceFiles()).containsExactly("deploy-guide.md");
    }

    @Test
    void draftDataCarriesQuestionAndMessageIdForEvalCase() {
        ChatFeedbackEntity feedback = new ChatFeedbackEntity("server-uuid", 457L, user.getId(), "team-1", -1, null);
        when(feedbackRepository.findById(9L)).thenReturn(Optional.of(feedback));
        ChatMessageEntity answer = Mockito.mock(ChatMessageEntity.class);
        when(answer.getConversation()).thenReturn(new ChatConversation("server-uuid", user, "测试", "rag", "team-1"));
        when(answer.getClientRequestId()).thenReturn("req-1");
        when(answer.getSourcesJson()).thenReturn("[]");
        when(messageRepository.findById(457L)).thenReturn(Optional.of(answer));
        ChatMessageEntity question = Mockito.mock(ChatMessageEntity.class);
        when(question.getContent()).thenReturn("原提问");
        when(messageRepository.findByConversationIdAndClientRequestIdAndRole("server-uuid", "req-1", "user"))
                .thenReturn(Optional.of(question));

        ChatFeedbackService.FeedbackDraftData data = service.draftData(9L);

        assertThat(data.question()).isEqualTo("原提问");
        assertThat(data.messageId()).isEqualTo(457L);
    }
}
