package com.example.workbench.conversation;

import com.example.workbench.auth.AppUser;
import com.example.workbench.auth.UserConversationScope;
import com.example.workbench.memory.ConversationMemory;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@Service
public class ConversationService {

    private final ChatConversationRepository conversationRepository;
    private final ChatMessageRepository messageRepository;
    private final ConversationMemory conversationMemory;
    private final ConversationExecutionRegistry executionRegistry;
    private final ObjectMapper objectMapper;

    public ConversationService(
            ChatConversationRepository conversationRepository,
            ChatMessageRepository messageRepository,
            ConversationMemory conversationMemory,
            ConversationExecutionRegistry executionRegistry,
            ObjectMapper objectMapper
    ) {
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
        this.conversationMemory = conversationMemory;
        this.executionRegistry = executionRegistry;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public List<ConversationResponse> list(AppUser user) {
        // 所有查询均按 userId 限制，不能根据会话 ID 直接暴露其他用户的历史。
        return conversationRepository.findByUserIdOrderByUpdatedAtDesc(user.getId()).stream()
                .map(this::response)
                .toList();
    }

    @Transactional
    public ConversationResponse create(AppUser user, ConversationRequest request) {
        ChatConversation conversation = conversationRepository.findByIdAndUserId(request.id(), user.getId())
                .orElseGet(() -> new ChatConversation(request.id(), user, request.title(), request.mode()));
        conversation.touch(request.title(), request.mode());
        return response(conversationRepository.save(conversation));
    }

    @Transactional(readOnly = true)
    public List<MessageResponse> messages(AppUser user, String conversationId) {
        ownedConversation(user, conversationId);
        return messageRepository.findByConversationIdOrderByCreatedAtAsc(conversationId).stream()
                .map(this::messageResponse)
                .toList();
    }

    @Transactional
    public void delete(AppUser user, String conversationId) {
        ownedConversation(user, conversationId);
        // 先取消正在生成的任务，避免删除后模型迟到结果重新写入会话。
        cancel(user, conversationId);
        messageRepository.deleteByConversationId(conversationId);
        conversationRepository.deleteById(conversationId);
    }

    public void stop(AppUser user, String conversationId) {
        ownedConversation(user, conversationId);
        // 停止只取消本次生成并清理内存上下文，历史消息仍保留。
        cancel(user, conversationId);
    }

    @Transactional
    public void recordUserMessage(AppUser user, String conversationId, String title, String mode, String content) {
        // 用户首次发言时创建会话；之后仅更新模式和默认标题。
        ChatConversation conversation = getOrCreate(user, conversationId, title, mode);
        messageRepository.save(new ChatMessageEntity(conversation, "user", content, "[]", "[]"));
    }

    @Transactional
    public boolean recordAssistantMessage(AppUser user, String conversationId, String mode, String content, Object sources, Object toolCalls) {
        ChatConversation conversation = conversationRepository.findByIdAndUserId(conversationId, user.getId())
                .orElse(null);
        if (conversation == null) {
            // 会话被删除后不自动重建，调用方据此丢弃模型的迟到结果。
            return false;
        }
        conversation.touch(null, mode);
        conversationRepository.save(conversation);
        messageRepository.save(new ChatMessageEntity(conversation, "assistant", content, json(sources), json(toolCalls)));
        return true;
    }

    private ChatConversation getOrCreate(AppUser user, String id, String title, String mode) {
        ChatConversation conversation = conversationRepository.findByIdAndUserId(id, user.getId())
                .orElse(null);
        if (conversation == null) {
            conversation = new ChatConversation(id, user, title == null || title.isBlank() ? "新的对话" : title, mode);
        } else {
            conversation.touch("新的对话".equals(conversation.getTitle()) ? title : null, mode);
        }
        return conversationRepository.save(conversation);
    }

    private ChatConversation ownedConversation(AppUser user, String conversationId) {
        // 对未归属当前用户的会话统一返回 404，避免泄露会话是否存在。
        return conversationRepository.findByIdAndUserId(conversationId, user.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "会话不存在"));
    }

    private void cancel(AppUser user, String conversationId) {
        String scope = UserConversationScope.id(user, conversationId);
        // 执行注册表负责阻止后续结果写入；内存记忆负责阻止上下文残留。
        executionRegistry.cancel(scope);
        conversationMemory.remove(scope);
    }

    private ConversationResponse response(ChatConversation conversation) {
        return new ConversationResponse(conversation.getId(), conversation.getTitle(), conversation.getMode(), conversation.getUpdatedAt());
    }

    private MessageResponse messageResponse(ChatMessageEntity message) {
        return new MessageResponse(message.getId(), message.getRole(), message.getContent(), readJson(message.getSourcesJson()), readJson(message.getToolCallsJson()), message.getCreatedAt());
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? List.of() : value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to serialize chat metadata", exception);
        }
    }

    private JsonNode readJson(String value) {
        try {
            return objectMapper.readTree(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to read stored chat metadata", exception);
        }
    }
}
