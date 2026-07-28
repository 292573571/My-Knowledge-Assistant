package com.example.workbench.rag;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "rag_quality_audits")
public class RagQualityAuditEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "conversation_id", nullable = false, length = 255)
    private String conversationId;

    @Column(name = "answer_length", nullable = false)
    private int answerLength;

    @Column(name = "source_count", nullable = false)
    private int sourceCount;

    @Column(nullable = false, length = 32)
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
