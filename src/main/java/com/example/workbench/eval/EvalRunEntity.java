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
import org.hibernate.annotations.Comment;

@Entity
@Table(name = "eval_runs")
@Comment("RAG 评测运行汇总表")
public class EvalRunEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Comment("评测运行主键")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = true)
    @JoinColumn(name = "owner_user_id")
    @Comment("所属用户主键，空值表示历史运行")
    private AppUser owner;

    @Column(name = "run_id", nullable = false, unique = true, length = 64)
    @Comment("评测运行业务标识")
    private String runId;

    @Column(nullable = false)
    @Comment("是否启用增强检索")
    private boolean enhanced;

    @Column(name = "judge_enabled", nullable = false)
    @Comment("是否启用大模型裁判")
    private boolean judgeEnabled;

    @Column(name = "total_cases", nullable = false)
    @Comment("评测用例总数")
    private int total;

    @Column(nullable = false)
    @Comment("通过用例数量")
    private int passed;

    @Column(nullable = false)
    @Comment("失败用例数量")
    private int failed;

    @Column(name = "pass_rate", nullable = false)
    @Comment("通过率")
    private double passRate;

    @Column(name = "retrieval_hit_rate", nullable = false)
    @Comment("检索命中率")
    private double retrievalHitRate;

    @Column(name = "citation_correctness_rate", nullable = false)
    @Comment("引用正确率")
    private double citationCorrectnessRate;

    @Column(name = "key_point_coverage_rate", nullable = false)
    @Comment("关键点覆盖率")
    private double keyPointCoverageRate;

    @Column(name = "unsupported_answer_rate", nullable = false)
    @Comment("无依据回答率")
    private double unsupportedAnswerRate;

    @Column(name = "model_fallback_rate", nullable = false)
    @Comment("模型兜底率")
    private double modelFallbackRate;

    @Column(name = "refusal_correctness_rate", nullable = false)
    @Comment("拒答正确率")
    private double refusalCorrectnessRate;

    @Column(name = "created_at", nullable = false)
    @Comment("运行时间")
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
