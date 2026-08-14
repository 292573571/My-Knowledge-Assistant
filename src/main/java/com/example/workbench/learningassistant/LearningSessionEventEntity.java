package com.example.workbench.learningassistant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import org.hibernate.annotations.Comment;

@Entity
@Table(name = "learning_session_events", uniqueConstraints = @UniqueConstraint(
        name = "uk_learning_session_event_request", columnNames = {"session_id", "client_request_id"}))
@Comment("学习会话事件幂等表")
public class LearningSessionEventEntity {
    @Id
    @Column(name = "event_id", nullable = false, length = 64)
    @Comment("学习事件主键")
    private String eventId;

    @Column(name = "session_id", nullable = false, length = 64)
    @Comment("所属学习会话")
    private String sessionId;

    @Column(name = "user_id", nullable = false)
    @Comment("所属用户主键")
    private Long userId;

    @Column(name = "workspace_id", nullable = false, length = 36)
    @Comment("所属知识空间")
    private String workspaceId;

    @Column(name = "client_request_id", nullable = false, length = 100)
    @Comment("客户端请求幂等标识")
    private String clientRequestId;

    @Column(name = "event_type", nullable = false, length = 32)
    @Comment("学习事件类型")
    private String eventType;

    @Column(name = "payload_json", nullable = false, columnDefinition = "text")
    @Comment("事件响应快照")
    private String payloadJson;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Comment("事件创建时间")
    private Instant createdAt;

    protected LearningSessionEventEntity() {
    }

    public LearningSessionEventEntity(String eventId, String sessionId, Long userId, String workspaceId,
                                      String clientRequestId, String eventType, String payloadJson) {
        this.eventId = eventId;
        this.sessionId = sessionId;
        this.userId = userId;
        this.workspaceId = workspaceId;
        this.clientRequestId = clientRequestId;
        this.eventType = eventType;
        this.payloadJson = payloadJson;
        this.createdAt = Instant.now();
    }

    public String getPayloadJson() { return payloadJson; }
}
