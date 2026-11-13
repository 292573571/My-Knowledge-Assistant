package com.example.workbench.conversation;

import com.example.workbench.auth.AppUser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * 消息反馈：用户对 AI 回答提交 👍/👎，管理员浏览差评并沉淀为评测草稿。
 */
@Service
public class ChatFeedbackService {

    private final ChatFeedbackRepository feedbackRepository;
    private final ConversationService conversationService;
    private final ChatMessageRepository messageRepository;
    private final ObjectMapper objectMapper;

    public ChatFeedbackService(ChatFeedbackRepository feedbackRepository, ConversationService conversationService,
                               ChatMessageRepository messageRepository, ObjectMapper objectMapper) {
        this.feedbackRepository = feedbackRepository;
        this.conversationService = conversationService;
        this.messageRepository = messageRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public FeedbackResponse submit(AppUser user, String workspaceId, String conversationId, Long messageId,
                                   int rating, String reason) {
        if (rating != 1 && rating != -1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "rating 只能为 1 或 -1");
        }
        ChatConversation conversation = conversationService.requireOwnedConversation(user, workspaceId, conversationId);
        ChatMessageEntity message = messageRepository.findById(messageId)
                .filter(entity -> entity.getConversation().getId().equals(conversation.getId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "消息不存在"));
        if (!"assistant".equals(message.getRole())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "只能对 AI 回答反馈");
        }
        String normalizedReason = reason == null || reason.isBlank() ? null : reason.strip();
        ChatFeedbackEntity feedback = feedbackRepository.findByMessageIdAndUserId(messageId, user.getId())
                .map(existing -> {
                    existing.update(rating, normalizedReason);
                    return existing;
                })
                .orElseGet(() -> new ChatFeedbackEntity(conversation.getId(), messageId, user.getId(),
                        workspaceId, rating, normalizedReason));
        return toResponse(feedbackRepository.save(feedback));
    }

    @Transactional(readOnly = true)
    public FeedbackResponse mine(AppUser user, Long messageId) {
        return feedbackRepository.findByMessageIdAndUserId(messageId, user.getId())
                .map(this::toResponse)
                .orElse(null);
    }

    @Transactional(readOnly = true)
    public List<AdminFeedbackItem> listForAdmin(String workspaceId, Integer rating) {
        List<ChatFeedbackEntity> items = feedbackRepository.findTop200ByWorkspaceIdOrderByUpdatedAtDesc(workspaceId);
        List<AdminFeedbackItem> result = new ArrayList<>();
        for (ChatFeedbackEntity feedback : items) {
            if (rating != null && !rating.equals(feedback.getRating())) continue;
            result.add(toAdminItem(feedback));
        }
        return result;
    }

    /** 管理员把一条反馈转成评测草稿所需的数据（问题 + 回答 + 引用文件名）。 */
    @Transactional(readOnly = true)
    public FeedbackDraftData draftData(Long feedbackId) {
        ChatFeedbackEntity feedback = feedbackRepository.findById(feedbackId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "反馈不存在"));
        ChatMessageEntity answer = messageRepository.findById(feedback.getMessageId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "原消息不存在"));
        // 用户消息与助手消息共用 clientRequestId，用它找回原提问。
        String question = "";
        if (answer.getClientRequestId() != null) {
            question = messageRepository
                    .findByConversationIdAndClientRequestIdAndRole(answer.getConversation().getId(),
                            answer.getClientRequestId(), "user")
                    .map(msg -> msg.getContent() == null ? "" : msg.getContent())
                    .orElse("");
        }
        return new FeedbackDraftData(feedback.getId(), feedback.getMessageId(), question, answer.getContent(),
                sourceFileNames(answer.getSourcesJson()));
    }

    private AdminFeedbackItem toAdminItem(ChatFeedbackEntity feedback) {
        String question = "";
        String answer = "";
        List<String> sourceFiles = List.of();
        Optional<ChatMessageEntity> message = messageRepository.findById(feedback.getMessageId());
        if (message.isPresent()) {
            ChatMessageEntity entity = message.get();
            answer = entity.getContent() == null ? "" : entity.getContent();
            sourceFiles = sourceFileNames(entity.getSourcesJson());
            if (entity.getClientRequestId() != null) {
                question = messageRepository
                        .findByConversationIdAndClientRequestIdAndRole(entity.getConversation().getId(),
                                entity.getClientRequestId(), "user")
                        .map(msg -> msg.getContent() == null ? "" : msg.getContent())
                        .orElse("");
            }
        }
        return new AdminFeedbackItem(feedback.getId(), feedback.getMessageId(), feedback.getRating(),
                feedback.getReason(), question, answer, sourceFiles, feedback.getUpdatedAt().toString());
    }

    private List<String> sourceFileNames(String sourcesJson) {
        if (sourcesJson == null || sourcesJson.isBlank()) return List.of();
        try {
            List<SourceNameView> sources = objectMapper.readValue(sourcesJson, new TypeReference<>() { });
            return sources.stream()
                    .map(source -> source.fileName() != null && !source.fileName().isBlank() ? source.fileName() : source.file())
                    .filter(name -> name != null && !name.isBlank())
                    .distinct()
                    .toList();
        } catch (Exception exception) {
            return List.of();
        }
    }

    private FeedbackResponse toResponse(ChatFeedbackEntity feedback) {
        return new FeedbackResponse(feedback.getId(), feedback.getMessageId(), feedback.getRating(),
                feedback.getReason(), feedback.getUpdatedAt().toString());
    }

    /** sources_json 元素的宽容视图：新老字段名都兜住。 */
    record SourceNameView(String fileName, String file) { }

    public record FeedbackResponse(Long id, Long messageId, int rating, String reason, String updatedAt) { }

    public record AdminFeedbackItem(Long id, Long messageId, int rating, String reason,
                                    String question, String answer, List<String> sourceFiles, String updatedAt) { }

    public record FeedbackDraftData(Long feedbackId, Long messageId, String question, String answer,
                                    List<String> sourceFiles) { }
}
