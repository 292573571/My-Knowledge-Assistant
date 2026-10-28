package com.example.workbench.observability;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.Comment;

@Entity
@Table(name = "model_usage_events", indexes = {
        @Index(name = "idx_model_usage_user_created", columnList = "user_id,created_at"),
        @Index(name = "idx_model_usage_created", columnList = "created_at")
})
@Comment("模型调用用量事件表")
public class ModelUsageEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Comment("用量事件主键")
    private Long id;

    @Column(name = "user_id")
    @Comment("调用用户主键；系统内部调用为空")
    private Long userId;

    @Column(name = "conversation_id", length = 128)
    @Comment("关联会话标识；工具型调用为 default")
    private String conversationId;

    @Column(nullable = false, length = 160)
    @Comment("实际使用的模型名")
    private String model;

    @Column(nullable = false, length = 16)
    @Comment("调用方式：sync 或 stream")
    private String mode;

    @Column(name = "prompt_tokens", nullable = false)
    @Comment("输入 token 数")
    private int promptTokens;

    @Column(name = "completion_tokens", nullable = false)
    @Comment("输出 token 数")
    private int completionTokens;

    @Column(name = "total_tokens", nullable = false)
    @Comment("总 token 数")
    private int totalTokens;

    @Column(name = "duration_ms", nullable = false)
    @Comment("调用耗时毫秒")
    private long durationMs;

    @Column(nullable = false)
    @Comment("调用是否成功")
    private boolean success;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Comment("记录时间")
    private Instant createdAt;

    public ModelUsageEvent() {
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getConversationId() {
        return conversationId;
    }

    public void setConversationId(String conversationId) {
        this.conversationId = conversationId;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public int getPromptTokens() {
        return promptTokens;
    }

    public void setPromptTokens(int promptTokens) {
        this.promptTokens = promptTokens;
    }

    public int getCompletionTokens() {
        return completionTokens;
    }

    public void setCompletionTokens(int completionTokens) {
        this.completionTokens = completionTokens;
    }

    public int getTotalTokens() {
        return totalTokens;
    }

    public void setTotalTokens(int totalTokens) {
        this.totalTokens = totalTokens;
    }

    public long getDurationMs() {
        return durationMs;
    }

    public void setDurationMs(long durationMs) {
        this.durationMs = durationMs;
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
