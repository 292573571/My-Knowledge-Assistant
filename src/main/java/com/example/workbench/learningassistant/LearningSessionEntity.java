package com.example.workbench.learningassistant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.time.Instant;
import org.hibernate.annotations.Comment;

@Entity
@Table(name = "learning_sessions", uniqueConstraints = @UniqueConstraint(
        name = "uk_learning_session_scope", columnNames = {"user_id", "workspace_id", "session_id"}))
@Comment("统一学习会话元数据表")
public class LearningSessionEntity {
    @Id
    @Column(name = "session_id", nullable = false, length = 64)
    @Comment("学习会话主键")
    private String sessionId;

    @Column(name = "user_id", nullable = false)
    @Comment("所属用户主键")
    private Long userId;

    @Column(name = "workspace_id", nullable = false, length = 36)
    @Comment("所属知识空间")
    private String workspaceId;

    @Column(name = "conversation_id", nullable = false, length = 64)
    @Comment("关联聊天会话标识")
    private String conversationId;

    @Column(name = "title", nullable = false, length = 120)
    @Comment("学习会话标题")
    private String title;

    @Column(name = "topic", length = 120)
    @Comment("当前学习主题")
    private String topic;

    @Column(name = "mode", nullable = false, length = 24)
    @Comment("学习模式")
    private String mode;

    @Column(name = "stage", nullable = false, length = 24)
    @Comment("当前学习阶段")
    private String stage;

    @Column(name = "status", nullable = false, length = 24)
    @Comment("学习会话状态")
    private String status;

    @Column(name = "user_level", nullable = false, length = 24)
    @Comment("用户学习水平")
    private String userLevel;

    @Column(name = "expires_at")
    @Comment("会话过期时间")
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Comment("会话创建时间")
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    @Comment("会话更新时间")
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    @Comment("乐观锁版本")
    private Long version;

    protected LearningSessionEntity() {
    }

    public LearningSessionEntity(String sessionId, Long userId, String workspaceId, String conversationId,
                                 String title, String topic, LearningMode mode, String userLevel) {
        this.sessionId = sessionId;
        this.userId = userId;
        this.workspaceId = workspaceId;
        this.conversationId = conversationId;
        this.title = title;
        this.topic = topic;
        this.mode = mode.name();
        this.stage = "CHAT";
        this.status = "ACTIVE";
        this.userLevel = userLevel;
        this.createdAt = Instant.now();
        this.updatedAt = createdAt;
    }

    public String getSessionId() { return sessionId; }
    public Long getUserId() { return userId; }
    public String getWorkspaceId() { return workspaceId; }
    public String getConversationId() { return conversationId; }
    public String getTitle() { return title; }
    public String getTopic() { return topic; }
    public LearningMode getMode() { return LearningMode.valueOf(mode); }
    public String getStage() { return stage; }
    public String getStatus() { return status; }
    public String getUserLevel() { return userLevel; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void touch(LearningMode nextMode, String nextTopic, String nextStage, String nextStatus) {
        if (nextMode != null) mode = nextMode.name();
        if (nextTopic != null && !nextTopic.isBlank()) topic = nextTopic;
        if (nextStage != null && !nextStage.isBlank()) stage = nextStage;
        if (nextStatus != null && !nextStatus.isBlank()) status = nextStatus;
        updatedAt = Instant.now();
    }
}
