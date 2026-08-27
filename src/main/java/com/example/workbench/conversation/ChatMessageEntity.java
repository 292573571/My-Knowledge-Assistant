package com.example.workbench.conversation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.Comment;

@Entity
@Table(name = "chat_messages")
@Comment("聊天消息表")
public class ChatMessageEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Comment("消息主键")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "conversation_id", nullable = false)
    @Comment("所属会话主键")
    private ChatConversation conversation;

    @Column(name = "role", nullable = false, length = 16)
    @Comment("消息角色")
    private String role;

    @Column(name = "content", nullable = false, columnDefinition = "text")
    @Comment("消息正文")
    private String content;

    @Column(name = "sources_json", nullable = false, columnDefinition = "text")
    @Comment("引用来源 JSON")
    private String sourcesJson;

    @Column(name = "tool_calls_json", nullable = false, columnDefinition = "text")
    @Comment("工具调用 JSON")
    private String toolCallsJson;

    @Column(name = "client_request_id", length = 100)
    @Comment("客户端请求幂等标识")
    private String clientRequestId;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Comment("创建时间")
    private Instant createdAt;

    protected ChatMessageEntity() {
    }

    public ChatMessageEntity(ChatConversation conversation, String role, String content, String sourcesJson, String toolCallsJson) {
        this(conversation, role, content, sourcesJson, toolCallsJson, null);
    }

    public ChatMessageEntity(ChatConversation conversation, String role, String content, String sourcesJson,
                             String toolCallsJson, String clientRequestId) {
        this.conversation = conversation;
        this.role = role;
        this.content = content;
        this.sourcesJson = sourcesJson;
        this.toolCallsJson = toolCallsJson;
        this.clientRequestId = clientRequestId;
        this.createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getRole() { return role; }
    public String getContent() { return content; }
    public String getSourcesJson() { return sourcesJson; }
    public String getToolCallsJson() { return toolCallsJson; }
    public Instant getCreatedAt() { return createdAt; }
    public String getClientRequestId() { return clientRequestId; }
}
