package com.example.workbench.agent;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import org.hibernate.annotations.Comment;

@Entity
@Table(name = "teaching_attempts")
@Comment("教学检查与实践状态表")
class TeachingAttemptEntity {

    @Id
    @Column(name = "check_id", length = 36)
    @Comment("理解检查业务标识")
    private String checkId;

    @Column(name = "owner_key", nullable = false, length = 80)
    @Comment("用户所有权标识")
    private String ownerKey;

    @Column(name = "workspace_id", nullable = false, length = 36)
    @Comment("所属知识空间")
    private String workspaceId;

    @Column(name = "session_id", nullable = false, length = 64)
    @Comment("教学会话标识")
    private String sessionId;

    @Column(nullable = false, length = 200)
    @Comment("教学主题")
    private String topic;

    @Column(nullable = false, length = 2000)
    @Comment("理解检查问题")
    private String question;

    @Column(name = "expires_at", nullable = false)
    @Comment("状态过期时间")
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Comment("创建时间")
    private Instant createdAt;

    @Column(columnDefinition = "text")
    @Comment("理解检查提交答案")
    private String answer;

    @Column(name = "check_completed", nullable = false)
    @Comment("理解检查是否完成")
    private boolean checkCompleted;

    @Column(name = "response_json", columnDefinition = "text")
    @Comment("理解检查响应快照")
    private String responseJson;

    @Column(name = "practice_id", unique = true, length = 36)
    @Comment("实践题业务标识")
    private String practiceId;

    @Column(name = "practice_question", length = 2000)
    @Comment("实践题问题")
    private String practiceQuestion;

    @Column(name = "practice_answer", columnDefinition = "text")
    @Comment("实践题提交答案")
    private String practiceAnswer;

    @Column(name = "practice_completed", nullable = false)
    @Comment("实践题是否完成")
    private boolean practiceCompleted;

    @Column(name = "practice_response_json", columnDefinition = "text")
    @Comment("实践题响应快照")
    private String practiceResponseJson;

    @Version
    @Comment("乐观锁版本")
    private long version;

    protected TeachingAttemptEntity() {
    }

    TeachingAttemptEntity(TeachingAttemptState state, String responseJson, String practiceResponseJson) {
        update(state, responseJson, practiceResponseJson);
        this.createdAt = state.createdAt;
    }

    void update(TeachingAttemptState state, String responseJson, String practiceResponseJson) {
        this.checkId = state.checkId;
        this.ownerKey = state.ownerKey;
        this.workspaceId = state.workspaceId;
        this.sessionId = state.sessionId;
        this.topic = state.topic;
        this.question = state.question;
        this.expiresAt = state.expiresAt;
        this.answer = state.answer;
        this.checkCompleted = state.checkCompleted;
        this.responseJson = responseJson;
        this.practiceId = state.practiceId;
        this.practiceQuestion = state.practiceQuestion;
        this.practiceAnswer = state.practiceAnswer;
        this.practiceCompleted = state.practiceCompleted;
        this.practiceResponseJson = practiceResponseJson;
    }

    String checkId() { return checkId; }
    String ownerKey() { return ownerKey; }
    String workspaceId() { return workspaceId; }
    String sessionId() { return sessionId; }
    String topic() { return topic; }
    String question() { return question; }
    Instant expiresAt() { return expiresAt; }
    Instant createdAt() { return createdAt; }
    String answer() { return answer; }
    boolean checkCompleted() { return checkCompleted; }
    String responseJson() { return responseJson; }
    String practiceId() { return practiceId; }
    String practiceQuestion() { return practiceQuestion; }
    String practiceAnswer() { return practiceAnswer; }
    boolean practiceCompleted() { return practiceCompleted; }
    String practiceResponseJson() { return practiceResponseJson; }
}
