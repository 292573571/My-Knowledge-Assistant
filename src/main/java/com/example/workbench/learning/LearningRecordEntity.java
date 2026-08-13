package com.example.workbench.learning;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.time.LocalDate;
import org.hibernate.annotations.Comment;

@Entity
@Table(name = "learning_records")
@Comment("结构化学习记录事实表")
class LearningRecordEntity {

    @Id
    @Column(length = 36)
    @Comment("学习记录主键")
    private String id;

    @Column(name = "owner_user_id", nullable = false)
    @Comment("记录所有者用户主键")
    private Long ownerUserId;

    @Column(name = "workspace_id", length = 36)
    @Comment("所属知识空间，空值表示历史未明确归属")
    private String workspaceId;

    @Column(name = "record_date", nullable = false)
    @Comment("学习记录日期")
    private LocalDate recordDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "record_type", nullable = false, length = 32)
    @Comment("学习记录类型：普通问答、教学检查、教学实践或历史记录")
    private LearningRecordType type;

    @Column(columnDefinition = "text")
    @Comment("用户问题或教学题目")
    private String question;

    @Column(columnDefinition = "text")
    @Comment("回答或用户提交答案")
    private String answer;

    @Column(length = 200)
    @Comment("学习主题")
    private String topic;

    @Column(name = "session_id", length = 128)
    @Comment("来源教学会话标识")
    private String sessionId;

    @Column(name = "conversation_id", length = 128)
    @Comment("来源聊天会话标识")
    private String conversationId;

    @Column(name = "message_id")
    @Comment("来源聊天消息主键")
    private Long messageId;

    @Column(name = "attempt_id", length = 36)
    @Comment("关联教学检查标识")
    private String attemptId;

    @Column(name = "practice_id", length = 36)
    @Comment("关联教学实践标识")
    private String practiceId;

    @Comment("本次得分")
    private Integer score;

    @Column(name = "max_score")
    @Comment("本次满分")
    private Integer maxScore;

    @Comment("是否通过")
    private Boolean passed;

    @Column(columnDefinition = "text")
    @Comment("教学反馈")
    private String feedback;

    @Column(name = "weak_point", columnDefinition = "text")
    @Comment("薄弱点")
    private String weakPoint;

    @Column(name = "review_explanation", columnDefinition = "text")
    @Comment("复习关键解释")
    private String reviewExplanation;

    @Column(name = "review_suggestion", columnDefinition = "text")
    @Comment("复习建议")
    private String reviewSuggestion;

    @Column(name = "sources_json", nullable = false, columnDefinition = "text")
    @Comment("回答来源快照 JSON")
    private String sourcesJson;

    @Column(name = "markdown", nullable = false, columnDefinition = "text")
    @Comment("该条记录的可读 Markdown 快照")
    private String markdown;

    @Column(name = "source_key", nullable = false, unique = true, length = 512)
    @Comment("来源幂等键")
    private String sourceKey;

    @Column(nullable = false)
    @Comment("是否由历史文件迁移而来")
    private boolean legacy;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Comment("记录创建时间")
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    @Comment("记录更新时间")
    private Instant updatedAt;

    @Version
    @Comment("记录版本")
    private long version;

    protected LearningRecordEntity() {
    }

    LearningRecordEntity(LearningRecordEntry entry) {
        this.id = entry.id();
        update(entry);
        this.createdAt = entry.createdAt() == null ? Instant.now() : entry.createdAt();
    }

    void update(LearningRecordEntry entry) {
        this.ownerUserId = entry.ownerUserId();
        this.workspaceId = entry.workspaceId();
        this.recordDate = entry.recordDate();
        this.type = entry.type();
        this.question = entry.question();
        this.answer = entry.answer();
        this.topic = entry.topic();
        this.sessionId = entry.sessionId();
        this.conversationId = entry.conversationId();
        this.messageId = entry.messageId();
        this.attemptId = entry.attemptId();
        this.practiceId = entry.practiceId();
        this.score = entry.score();
        this.maxScore = entry.maxScore();
        this.passed = entry.passed();
        this.feedback = entry.feedback();
        this.weakPoint = entry.weakPoint();
        this.reviewExplanation = entry.reviewExplanation();
        this.reviewSuggestion = entry.reviewSuggestion();
        this.sourcesJson = entry.sourcesJson() == null ? "[]" : entry.sourcesJson();
        this.markdown = entry.markdown();
        this.sourceKey = entry.sourceKey();
        this.legacy = entry.legacy();
        this.updatedAt = entry.updatedAt() == null ? Instant.now() : entry.updatedAt();
    }

    LearningRecordEntry toEntry() {
        return new LearningRecordEntry(id, ownerUserId, workspaceId, recordDate, type, question, answer, topic,
                sessionId, conversationId, messageId, attemptId, practiceId, score, maxScore, passed, feedback, weakPoint, reviewExplanation,
                reviewSuggestion, sourcesJson, markdown, sourceKey, legacy, createdAt, updatedAt);
    }

    String id() { return id; }
    Long ownerUserId() { return ownerUserId; }
    LocalDate recordDate() { return recordDate; }
    String workspaceId() { return workspaceId; }
    String sourceKey() { return sourceKey; }
}
