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

@Entity
@Table(name = "eval_cases")
public class EvalCaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_user_id", nullable = false)
    private AppUser owner;

    @Column(name = "case_id", nullable = false, length = 128)
    private String caseId;

    @Column(nullable = false, length = 64)
    private String mode;

    @Column(nullable = false, length = 64)
    private String type;

    @Column(nullable = false, length = 4000)
    private String question;

    @Column(name = "expect_no_answer", nullable = false)
    private boolean expectNoAnswer;

    @Column(name = "require_local_evidence", nullable = false)
    private boolean requireLocalEvidence;

    @Column(name = "allow_model_fallback", nullable = false)
    private boolean allowModelFallback;

    @Column(name = "expected_sources", nullable = false, columnDefinition = "TEXT")
    private String expectedSources;

    @Column(name = "expected_heading_paths", nullable = false, columnDefinition = "TEXT")
    private String expectedHeadingPaths;

    @Column(name = "expected_keywords", nullable = false, columnDefinition = "TEXT")
    private String expectedKeywords;

    @Column(name = "forbidden_keywords", nullable = false, columnDefinition = "TEXT")
    private String forbiddenKeywords;

    protected EvalCaseEntity() {
    }

    public EvalCaseEntity(AppUser owner) {
        this.owner = owner;
    }

    public Long getId() { return id; }
    public AppUser getOwner() { return owner; }
    public String getCaseId() { return caseId; }
    public String getMode() { return mode; }
    public String getType() { return type; }
    public String getQuestion() { return question; }
    public boolean isExpectNoAnswer() { return expectNoAnswer; }
    public boolean isRequireLocalEvidence() { return requireLocalEvidence; }
    public boolean isAllowModelFallback() { return allowModelFallback; }
    public String getExpectedSources() { return expectedSources; }
    public String getExpectedHeadingPaths() { return expectedHeadingPaths; }
    public String getExpectedKeywords() { return expectedKeywords; }
    public String getForbiddenKeywords() { return forbiddenKeywords; }

    public void update(EvalCaseRequest request, String expectedSources, String expectedHeadingPaths,
                       String expectedKeywords, String forbiddenKeywords) {
        this.caseId = request.caseId();
        this.mode = request.mode();
        this.type = request.type();
        this.question = request.question();
        this.expectNoAnswer = request.expectNoAnswer();
        this.requireLocalEvidence = request.requireLocalEvidence();
        this.allowModelFallback = request.allowModelFallback();
        this.expectedSources = expectedSources;
        this.expectedHeadingPaths = expectedHeadingPaths;
        this.expectedKeywords = expectedKeywords;
        this.forbiddenKeywords = forbiddenKeywords;
    }
}
