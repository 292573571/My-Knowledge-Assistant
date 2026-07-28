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

@Entity
@Table(name = "chat_messages")
public class ChatMessageEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "conversation_id", nullable = false)
    private ChatConversation conversation;

    @Column(name = "role", nullable = false, length = 16)
    private String role;

    @Column(name = "content", nullable = false, columnDefinition = "text")
    private String content;

    @Column(name = "sources_json", nullable = false, columnDefinition = "text")
    private String sourcesJson;

    @Column(name = "tool_calls_json", nullable = false, columnDefinition = "text")
    private String toolCallsJson;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ChatMessageEntity() {
    }

    public ChatMessageEntity(ChatConversation conversation, String role, String content, String sourcesJson, String toolCallsJson) {
        this.conversation = conversation;
        this.role = role;
        this.content = content;
        this.sourcesJson = sourcesJson;
        this.toolCallsJson = toolCallsJson;
        this.createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getRole() { return role; }
    public String getContent() { return content; }
    public String getSourcesJson() { return sourcesJson; }
    public String getToolCallsJson() { return toolCallsJson; }
    public Instant getCreatedAt() { return createdAt; }
}
