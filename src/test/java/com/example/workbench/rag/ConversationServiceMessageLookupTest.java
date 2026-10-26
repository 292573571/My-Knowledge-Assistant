package com.example.workbench.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.workbench.auth.AppUser;
import com.example.workbench.conversation.ChatConversation;
import com.example.workbench.conversation.ChatConversationRepository;
import com.example.workbench.conversation.ChatMessageEntity;
import com.example.workbench.conversation.ChatMessageRepository;
import com.example.workbench.conversation.ConversationExecutionRegistry;
import com.example.workbench.conversation.ConversationService;
import com.example.workbench.conversation.MessageResponse;
import com.example.workbench.memory.ConversationMemory;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * 会话历史加载的查询次数约束。
 *
 * <p>历史引用的展示名需要任务表才能还原。若逐条来源查询，打开一个含 N 条消息、
 * 每条 M 个引用的会话会产生 N×M 次任务表查询。这里锁定「整个请求只查一次」。</p>
 *
 * <p>放在 rag 包内是因为 {@link DocumentTaskRepository} 与 {@link DocumentTaskEntity}
 * 都是包内可见，跨包测试无法构造解析器。</p>
 */
class ConversationServiceMessageLookupTest {

    @Test
    void resolvesAllMessageSourcesWithASingleTaskQueryPerRequest() {
        ChatConversationRepository conversations = mock(ChatConversationRepository.class);
        ChatMessageRepository messages = mock(ChatMessageRepository.class);
        DocumentIngestionService ingestion = mock(DocumentIngestionService.class);
        DocumentTaskRepository tasks = mock(DocumentTaskRepository.class);
        DocumentDisplayNameResolver resolver = new DocumentDisplayNameResolver(tasks);
        when(ingestion.listIndexedDocuments()).thenReturn(List.of());
        when(tasks.findByWorkspaceIdAndTypeOrderByCreatedAtDesc("ws-1", DocumentTaskType.UPLOAD))
                .thenReturn(List.of());

        ConversationService service = new ConversationService(conversations, messages, new ConversationMemory(),
                new ConversationExecutionRegistry(), new ObjectMapper(), ingestion, resolver);

        AppUser user = new AppUser("alice", "Alice", "hash");
        ChatConversation conversation = new ChatConversation("id-1", user, "标题", "rag", "ws-1");
        when(conversations.findVisibleByIdAndUserAndWorkspace("conv-1", user.getId(), "ws-1", "personal-null"))
                .thenReturn(Optional.of(conversation));
        when(messages.findByConversationIdOrderByCreatedAtAsc("id-1")).thenReturn(List.of(
                message(conversation, "第一条回答"),
                message(conversation, "第二条回答")));

        List<MessageResponse> result = service.messages(user, "ws-1", "conv-1");

        assertThat(result).hasSize(2);
        // 2 条消息 × 2 个引用 = 4 次解析，但任务表只允许查询 1 次。
        verify(tasks, times(1)).findByWorkspaceIdAndTypeOrderByCreatedAtDesc("ws-1", DocumentTaskType.UPLOAD);
    }

    private ChatMessageEntity message(ChatConversation conversation, String content) {
        return new ChatMessageEntity(conversation, "assistant", content,
                "[{\"file\":\"a.pdf\",\"path\":\"docs/a.pdf\"},{\"file\":\"b.pdf\",\"path\":\"docs/b.pdf\"}]",
                "[]");
    }
}
