package com.example.workbench.conversation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.Comment;

/**
 * 用户对 AI 回答的 👍/👎 反馈。message_id 为 chat_messages 自增主键，
 * (message_id, user_id) 唯一：重复提交按更新处理。
 */
@Entity
@Table(name = "chat_feedback")
@Comment("聊天消息反馈表")
public class ChatFeedbackEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Comment("反馈主键")
    private Long id;

    @Column(name = "conversation_id", nullable = false, length = 64)
    @Comment("服务端会话主键（chat_conversations.id）")
    private String conversationId;

    @Column(name = "message_id", nullable = false)
    @Comment("被反馈的助手消息主键（chat_messages.id）")
    private Long messageId;

    @Column(name = "user_id", nullable = false)
    @Comment("反馈用户")
    private Long userId;

    @Column(name = "workspace_id", nullable = false, length = 36)
    @Comment("所属工作空间")
    private String workspaceId;

    @Column(name = "rating", nullable = false)
    @Comment("1=赞，-1=差评")
    private Integer rating;

    @Column(name = "reason", length = 500)
    @Comment("差评原因（可选）")
    private String reason;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Comment("首次反馈时间")
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    @Comment("最近一次反馈更新时间")
    private Instant updatedAt;

    protected ChatFeedbackEntity() {
    }

    public ChatFeedbackEntity(String conversationId, Long messageId, Long userId, String workspaceId,
                              Integer rating, String reason) {
        this.conversationId = conversationId;
        this.messageId = messageId;
        this.userId = userId;
        this.workspaceId = workspaceId;
        this.rating = rating;
        this.reason = reason;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public Long getId() { return id; }
    public String getConversationId() { return conversationId; }
    public Long getMessageId() { return messageId; }
    public Long getUserId() { return userId; }
    public String getWorkspaceId() { return workspaceId; }
    public Integer getRating() { return rating; }
    public String getReason() { return reason; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void update(Integer rating, String reason) {
        this.rating = rating;
        this.reason = reason;
        this.updatedAt = Instant.now();
    }
}
