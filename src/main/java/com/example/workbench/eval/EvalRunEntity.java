package com.example.workbench.eval;

import com.example.workbench.auth.AppUser;
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
@Table(name = "eval_runs")
public class EvalRunEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = true)
    @JoinColumn(name = "owner_user_id")
    private AppUser owner;

    @Column(name = "run_id", nullable = false, unique = true, length = 64)
    private String runId;

    @Column(nullable = false)
    private boolean enhanced;

    @Column(name = "judge_enabled", nullable = false)
    private boolean judgeEnabled;

    @Column(name = "total_cases", nullable = false)
    private int total;

    @Column(nullable = false)
    private int passed;

    @Column(nullable = false)
    private int failed;

    @Column(name = "pass_rate", nullable = false)
    private double passRate;

    @Column(name = "retrieval_hit_rate", nullable = false)
    private double retrievalHitRate;

    @Column(name = "citation_correctness_rate", nullable = false)
    private double citationCorrectnessRate;

    @Column(name = "key_point_coverage_rate", nullable = false)
    private double keyPointCoverageRate;

    @Column(name = "unsupported_answer_rate", nullable = false)
    private double unsupportedAnswerRate;

    @Column(name = "model_fallback_rate", nullable = false)
    private double modelFallbackRate;

    @Column(name = "refusal_correctness_rate", nullable = false)
    private double refusalCorrectnessRate;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected EvalRunEntity() {
    }

    EvalRunEntity(AppUser owner, EvalSummary summary, boolean enhanced, boolean judgeEnabled) {
        this.owner = owner;
        this.runId = summary.runId();
        this.enhanced = enhanced;
        this.judgeEnabled = judgeEnabled;
        this.total = summary.total();
        this.passed = summary.passed();
        this.failed = summary.failed();
        this.passRate = summary.passRate();
        this.retrievalHitRate = summary.retrievalHitRate();
        this.citationCorrectnessRate = summary.citationCorrectnessRate();
        this.keyPointCoverageRate = summary.keyPointCoverageRate();
        this.unsupportedAnswerRate = summary.unsupportedAnswerRate();
        this.modelFallbackRate = summary.modelFallbackRate();
        this.refusalCorrectnessRate = summary.refusalCorrectnessRate();
        this.createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getRunId() { return runId; }
    public boolean isEnhanced() { return enhanced; }
    public boolean isJudgeEnabled() { return judgeEnabled; }
    public int getTotal() { return total; }
    public int getPassed() { return passed; }
    public int getFailed() { return failed; }
    public double getPassRate() { return passRate; }
    public double getRetrievalHitRate() { return retrievalHitRate; }
    public double getCitationCorrectnessRate() { return citationCorrectnessRate; }
    public double getKeyPointCoverageRate() { return keyPointCoverageRate; }
    public double getUnsupportedAnswerRate() { return unsupportedAnswerRate; }
    public double getModelFallbackRate() { return modelFallbackRate; }
    public double getRefusalCorrectnessRate() { return refusalCorrectnessRate; }
    public Instant getCreatedAt() { return createdAt; }
}
