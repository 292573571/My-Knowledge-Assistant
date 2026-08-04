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
import org.hibernate.annotations.Comment;

@Entity
@Table(name = "eval_cases")
@Comment("RAG 评测用例表")
public class EvalCaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Comment("评测用例主键")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_user_id", nullable = false)
    @Comment("所属用户主键")
    private AppUser owner;

    @Column(name = "case_id", nullable = false, length = 128)
    @Comment("评测用例业务标识")
    private String caseId;

    @Column(nullable = false, length = 64)
    @Comment("评测模式")
    private String mode;

    @Column(nullable = false, length = 64)
    @Comment("评测类型")
    private String type;

    @Column(nullable = false, length = 4000)
    @Comment("评测问题")
    private String question;

    @Column(name = "expect_no_answer", nullable = false)
    @Comment("是否期望拒绝回答")
    private boolean expectNoAnswer;

    @Column(name = "require_local_evidence", nullable = false)
    @Comment("是否要求本地知识证据")
    private boolean requireLocalEvidence;

    @Column(name = "allow_model_fallback", nullable = false)
    @Comment("是否允许模型兜底")
    private boolean allowModelFallback;

    @Column(name = "expected_sources", nullable = false, columnDefinition = "TEXT")
    @Comment("期望来源规则")
    private String expectedSources;

    @Column(name = "expected_heading_paths", nullable = false, columnDefinition = "TEXT")
    @Comment("期望标题路径规则")
    private String expectedHeadingPaths;

    @Column(name = "expected_keywords", nullable = false, columnDefinition = "TEXT")
    @Comment("期望关键词规则")
    private String expectedKeywords;

    @Column(name = "forbidden_keywords", nullable = false, columnDefinition = "TEXT")
    @Comment("禁用关键词规则")
    private String forbiddenKeywords;

    @Column(name = "expected_page_numbers", columnDefinition = "TEXT")
    @Comment("期望命中的 PDF 页码")
    private String expectedPageNumbers;

    @Column(name = "expected_retrieval_keywords", columnDefinition = "TEXT")
    @Comment("同一检索候选必须包含的关键词")
    private String expectedRetrievalKeywords;

    @Column(name = "forbidden_retrieval_keywords", columnDefinition = "TEXT")
    @Comment("检索候选正文不得包含的噪音关键词")
    private String forbiddenRetrievalKeywords;

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
    public String getExpectedPageNumbers() { return expectedPageNumbers; }
    public String getExpectedRetrievalKeywords() { return expectedRetrievalKeywords; }
    public String getForbiddenRetrievalKeywords() { return forbiddenRetrievalKeywords; }

    public void update(EvalCaseRequest request, String expectedSources, String expectedHeadingPaths,
                       String expectedKeywords, String forbiddenKeywords, String expectedPageNumbers,
                       String expectedRetrievalKeywords, String forbiddenRetrievalKeywords) {
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
        this.expectedPageNumbers = expectedPageNumbers;
        this.expectedRetrievalKeywords = expectedRetrievalKeywords;
        this.forbiddenRetrievalKeywords = forbiddenRetrievalKeywords;
    }

    void update(EvalCaseRequest request, String expectedSources, String expectedHeadingPaths,
                String expectedKeywords, String forbiddenKeywords) {
        update(request, expectedSources, expectedHeadingPaths, expectedKeywords, forbiddenKeywords,
                "[]", "[]", "[]");
    }
}
