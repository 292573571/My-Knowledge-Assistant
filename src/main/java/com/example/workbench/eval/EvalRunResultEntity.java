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

@Entity
@Table(name = "eval_run_results")
public class EvalRunResultEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "eval_run_id", nullable = false)
    private EvalRunEntity run;

    @Column(name = "case_id", nullable = false, length = 128)
    private String caseId;

    @Column(name = "result_json", nullable = false, columnDefinition = "TEXT")
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
