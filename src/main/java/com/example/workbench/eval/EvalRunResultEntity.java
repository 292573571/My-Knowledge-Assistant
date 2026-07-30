package com.example.workbench.eval;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.Comment;

@Entity
@Table(name = "eval_run_results")
@Comment("评测运行明细结果表")
public class EvalRunResultEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Comment("评测结果主键")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "eval_run_id", nullable = false)
    @Comment("所属评测运行主键")
    private EvalRunEntity run;

    @Column(name = "case_id", nullable = false, length = 128)
    @Comment("评测用例标识")
    private String caseId;

    @Column(name = "result_json", nullable = false, columnDefinition = "TEXT")
    @Comment("评测结果 JSON")
    private String resultJson;

    protected EvalRunResultEntity() {
    }

    EvalRunResultEntity(EvalRunEntity run, String caseId, String resultJson) {
        this.run = run;
        this.caseId = caseId;
        this.resultJson = resultJson;
    }

    public String getResultJson() { return resultJson; }
}
