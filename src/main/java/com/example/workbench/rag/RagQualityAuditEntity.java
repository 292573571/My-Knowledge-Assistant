package com.example.workbench.rag;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.Comment;

@Entity
@Table(name = "rag_quality_audits")
@Comment("RAG 回答质量审计表")
public class RagQualityAuditEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Comment("质量审计主键")
    private Long id;

    @Column(name = "created_at", nullable = false)
    @Comment("审计时间")
    private Instant createdAt;

    @Column(name = "conversation_id", nullable = false, length = 255)
    @Comment("用户隔离后的会话标识")
    private String conversationId;

    @Column(name = "answer_length", nullable = false)
    @Comment("回答字符数量")
    private int answerLength;

    @Column(name = "source_count", nullable = false)
    @Comment("引用来源数量")
    private int sourceCount;

    @Column(nullable = false, length = 32)
    @Comment("质量审计状态")
    private String status;

    protected RagQualityAuditEntity() {
    }

    RagQualityAuditEntity(String conversationId, int answerLength, int sourceCount, String status) {
        this.createdAt = Instant.now();
        this.conversationId = conversationId == null ? "" : conversationId;
        this.answerLength = answerLength;
        this.sourceCount = sourceCount;
        this.status = status;
    }
}
