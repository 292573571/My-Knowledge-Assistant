package com.example.workbench.conversation;

import com.example.workbench.auth.AppUser;
import com.example.workbench.auth.UserConversationScope;
import com.example.workbench.memory.ConversationMemory;
import com.example.workbench.rag.DocumentIndexEntry;
import com.example.workbench.rag.DocumentIngestionService;
import com.example.workbench.rag.DocumentDisplayNameResolver;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
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
    private final DocumentIngestionService documentIngestionService;
    private final DocumentDisplayNameResolver displayNameResolver;

    @Autowired
    public ConversationService(
            ChatConversationRepository conversationRepository,
            ChatMessageRepository messageRepository,
            ConversationMemory conversationMemory,
            ConversationExecutionRegistry executionRegistry,
            ObjectMapper objectMapper,
            DocumentIngestionService documentIngestionService,
            DocumentDisplayNameResolver displayNameResolver
    ) {
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
        this.conversationMemory = conversationMemory;
        this.executionRegistry = executionRegistry;
        this.objectMapper = objectMapper;
        this.documentIngestionService = documentIngestionService;
        this.displayNameResolver = displayNameResolver;
    }

    /**
     * 保留测试和轻量调用方使用的旧构造器；Spring 使用上面的完整构造器注入索引服务。
     */
    public ConversationService(
            ChatConversationRepository conversationRepository,
            ChatMessageRepository messageRepository,
            ConversationMemory conversationMemory,
            ConversationExecutionRegistry executionRegistry,
            ObjectMapper objectMapper
    ) {
        this(conversationRepository, messageRepository, conversationMemory, executionRegistry, objectMapper, null, null);
    }

    @Transactional(readOnly = true)
    public List<ConversationResponse> list(AppUser user, String workspaceId) {
        // 所有查询均按 userId 限制，不能根据会话 ID 直接暴露其他用户的历史。
        return conversationRepository.findVisibleByUserAndWorkspace(user.getId(), workspaceId, personalWorkspaceId(user)).stream()
                .map(this::response)
                .toList();
    }

    @Transactional
    public ConversationResponse create(AppUser user, String workspaceId, ConversationRequest request) {
        ChatConversation conversation = findConversation(user, workspaceId, request.id())
                .orElseGet(() -> newConversation(user, workspaceId, request.id(), request.title(), request.mode()));
        conversation.touch(request.title(), request.mode());
        return response(conversationRepository.save(conversation));
    }

    @Transactional(readOnly = true)
    public List<MessageResponse> messages(AppUser user, String workspaceId, String conversationId) {
        ChatConversation conversation = ownedConversation(user, workspaceId, conversationId);
        return messageRepository.findByConversationIdOrderByCreatedAtAsc(conversation.getId()).stream()
                .map(message -> messageResponse(message, workspaceId))
                .toList();
    }

    @Transactional
    public void delete(AppUser user, String workspaceId, String conversationId) {
        ChatConversation conversation = ownedConversation(user, workspaceId, conversationId);
        // 先取消正在生成的任务，避免删除后模型迟到结果重新写入会话。
        cancel(user, workspaceId, conversationId);
        messageRepository.deleteByConversationId(conversation.getId());
        conversationRepository.deleteById(conversation.getId());
    }

    public void stop(AppUser user, String workspaceId, String conversationId) {
        ownedConversation(user, workspaceId, conversationId);
        // 停止只取消本次生成并清理内存上下文，历史消息仍保留。
        cancel(user, workspaceId, conversationId);
    }

    @Transactional
    public void recordUserMessage(AppUser user, String workspaceId, String conversationId, String title, String mode, String content) {
        // 用户首次发言时创建会话；之后仅更新模式和默认标题。
        ChatConversation conversation = getOrCreate(user, workspaceId, conversationId, title, mode);
        messageRepository.save(new ChatMessageEntity(conversation, "user", content, "[]", "[]"));
    }

    @Transactional
    public boolean recordAssistantMessage(AppUser user, String workspaceId, String conversationId, String mode, String content, Object sources, Object toolCalls) {
        ChatConversation conversation = findConversation(user, workspaceId, conversationId)
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

    private ChatConversation getOrCreate(AppUser user, String workspaceId, String id, String title, String mode) {
        ChatConversation conversation = findConversation(user, workspaceId, id)
                .orElse(null);
        if (conversation == null) {
            conversation = newConversation(user, workspaceId, id,
                    title == null || title.isBlank() ? "新的对话" : title, mode);
        } else {
            conversation.touch("新的对话".equals(conversation.getTitle()) ? title : null, mode);
        }
        return conversationRepository.save(conversation);
    }

    private ChatConversation newConversation(AppUser user, String workspaceId, String id, String title, String mode) {
        // 数据库使用服务端 UUID，全局主键不再直接信任客户端可复用的会话标识。
        return new ChatConversation(java.util.UUID.randomUUID().toString(), id, user, title, mode, workspaceId);
    }

    private ChatConversation ownedConversation(AppUser user, String workspaceId, String conversationId) {
        // 对未归属当前用户的会话统一返回 404，避免泄露会话是否存在。
        return findConversation(user, workspaceId, conversationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "会话不存在"));
    }

    private void cancel(AppUser user, String workspaceId, String conversationId) {
        String scope = executionScope(user, workspaceId, conversationId);
        // 执行注册表负责阻止后续结果写入；内存记忆负责阻止上下文残留。
        executionRegistry.cancel(scope);
        conversationMemory.remove(scope);
    }

    private Optional<ChatConversation> findConversation(AppUser user, String workspaceId, String conversationId) {
        return conversationRepository.findVisibleByIdAndUserAndWorkspace(
                conversationId, user.getId(), workspaceId, personalWorkspaceId(user));
    }

    public String executionScope(AppUser user, String workspaceId, String conversationId) {
        return UserConversationScope.id(user, workspaceId + ":" + conversationId);
    }

    private String personalWorkspaceId(AppUser user) {
        return "personal-" + user.getId();
    }

    private ConversationResponse response(ChatConversation conversation) {
        return new ConversationResponse(conversation.getClientConversationId(), conversation.getTitle(), conversation.getMode(), conversation.getUpdatedAt());
    }

    private MessageResponse messageResponse(ChatMessageEntity message, String workspaceId) {
        return new MessageResponse(message.getId(), message.getRole(), message.getContent(),
                normalizeSources(readJson(message.getSourcesJson()), workspaceId), readJson(message.getToolCallsJson()),
                message.getCreatedAt());
    }

    /**
     * 历史消息可能保存了旧版向量 metadata 中的 UUID 存储文件名。
     * 读取历史时按当前索引表的路径恢复原始文件名，避免旧引用永久显示 UUID。
     */
    private JsonNode normalizeSources(JsonNode sources, String workspaceId) {
        if (sources == null || !sources.isArray() || documentIngestionService == null || displayNameResolver == null) return sources;
        List<DocumentIndexEntry> entries = documentIngestionService.listIndexedDocuments().stream()
                .filter(entry -> workspaceId == null || workspaceId.isBlank() || workspaceId.equals(entry.workspaceId()))
                .toList();
        ArrayNode normalized = objectMapper.createArrayNode();
        for (JsonNode source : sources) {
            if (!source.isObject()) {
                normalized.add(source);
                continue;
            }
            ObjectNode copy = source.deepCopy();
            String displayName = displayNameResolver.resolve(text(copy, "file"), text(copy, "path"), workspaceId, entries);
            if (displayName != null && !displayName.isBlank()) copy.put("file", displayName);
            normalized.add(copy);
        }
        return normalized;
    }

    private String text(ObjectNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? "" : value.asText("");
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
