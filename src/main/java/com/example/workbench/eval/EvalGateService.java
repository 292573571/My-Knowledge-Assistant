package com.example.workbench.eval;

import com.example.workbench.auth.AppUser;
import com.example.workbench.auth.AppUserRepository;
import com.example.workbench.auth.SystemRole;
import com.example.workbench.modelconfig.ModelConfigContext;
import java.io.IOException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 发版前的非交互评测门禁：由部署脚本携带运维令牌调用，跑基线题并按阈值判定放行或阻断。
 */
@Service
public class EvalGateService {

    private static final Logger log = LoggerFactory.getLogger(EvalGateService.class);

    private final EvalCaseService evalCaseService;
    private final EvalRunner evalRunner;
    private final EvalExecutionGuard executionGuard;
    private final AppUserRepository userRepository;
    private final ModelConfigContext modelConfigContext;
    private final boolean enabled;
    private final String opsToken;
    private final String ownerAccount;
    private final String defaultSuite;

    /**
     * 创建发版评测门禁服务。
     *
     * @param evalCaseService 评测题库服务
     * @param evalRunner 评测执行器
     * @param executionGuard 并发与规模守卫
     * @param userRepository 用户仓储，用于解析门禁题库归属账号
     * @param modelConfigContext 模型配置上下文，非 HTTP 认证链路需要显式设置
     * @param enabled 是否启用门禁入口
     * @param opsToken 运维令牌，未配置时入口不可用
     * @param ownerAccount 题库归属账号，为空时取最早的超级管理员
     * @param defaultSuite 默认评测集合
     */
    public EvalGateService(EvalCaseService evalCaseService,
                           EvalRunner evalRunner,
                           EvalExecutionGuard executionGuard,
                           AppUserRepository userRepository,
                           ModelConfigContext modelConfigContext,
                           @Value("${workbench.eval.gate.enabled:false}") boolean enabled,
                           @Value("${workbench.eval.gate.ops-token:}") String opsToken,
                           @Value("${workbench.eval.gate.owner-account:}") String ownerAccount,
                           @Value("${workbench.eval.gate.suite:REGRESSION}") String defaultSuite) {
        this.evalCaseService = evalCaseService;
        this.evalRunner = evalRunner;
        this.executionGuard = executionGuard;
        this.userRepository = userRepository;
        this.modelConfigContext = modelConfigContext;
        this.enabled = enabled;
        this.opsToken = opsToken == null ? "" : opsToken.trim();
        this.ownerAccount = ownerAccount == null ? "" : ownerAccount.trim();
        this.defaultSuite = defaultSuite == null || defaultSuite.isBlank() ? "REGRESSION" : defaultSuite.trim();
    }

    /**
     * 校验运维令牌，使用常量时间比较避免时序侧信道。
     *
     * @param presented 请求携带的令牌
     * @return 是否允许调用
     */
    public boolean authorized(String presented) {
        if (opsToken.isEmpty() || presented == null || presented.isBlank()) {
            return false;
        }
        byte[] expected = opsToken.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] actual = presented.trim().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return java.security.MessageDigest.isEqual(expected, actual);
    }

    /**
     * 执行一次门禁评测。题库为空时判定不通过，避免"没有题就等于通过"。
     *
     * @param suite 评测集合，空值使用配置默认值
     * @return 门禁结果
     */
    public GateResult run(String suite) {
        if (!enabled) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "评测门禁未启用");
        }
        String requestedSuite = suite == null || suite.isBlank() ? defaultSuite : suite.trim();
        AppUser owner = resolveOwner();
        List<EvalCase> cases = evalCaseService.selectedCases(owner, null, requestedSuite, null);
        if (cases.isEmpty()) {
            log.warn("Eval gate has no cases suite={} owner={}", requestedSuite, owner.getAccount());
            return new GateResult(false, requestedSuite, owner.getAccount(), 0, 0, 0.0, 0.0, 0.0, 0.0,
                    null, List.of("评测集合 " + requestedSuite + " 没有可用题目，无法放行"), List.of());
        }
        modelConfigContext.set(owner.getId());
        try {
            EvalSummary summary = executionGuard.execute(owner, cases.size(),
                    () -> {
                        try {
                            return evalRunner.run(cases, false, owner);
                        } catch (IOException exception) {
                            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                                    "评测执行失败: " + exception.getMessage());
                        }
                    });
            log.info("Eval gate finished suite={} owner={} total={} passRate={} gatePassed={}",
                    requestedSuite, owner.getAccount(), summary.total(), summary.passRate(), summary.gatePassed());
            return new GateResult(summary.gatePassed(), requestedSuite, owner.getAccount(), summary.total(),
                    summary.passed(), summary.passRate(), summary.recallAt5(), summary.mrr(),
                    summary.unsupportedAnswerRate(), summary.runId(), summary.gateFailures(),
                    failedCases(summary));
        } finally {
            modelConfigContext.clear();
        }
    }

    /** 汇总未通过题目，便于发版日志直接定位是哪道题退化，无需再登库翻明细。 */
    private List<GateFailure> failedCases(EvalSummary summary) {
        return summary.results().stream()
                .filter(result -> !result.passed())
                .map(result -> new GateFailure(result.id(), result.question(), result.finalScore(),
                        result.reason(), result.expectedSources(), result.actualSources(),
                        result.missingKeywords()))
                .toList();
    }

    private AppUser resolveOwner() {
        if (!ownerAccount.isEmpty()) {
            return userRepository.findByAccount(ownerAccount)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                            "评测门禁题库账号不存在: " + ownerAccount));
        }
        return userRepository.findFirstBySystemRole(SystemRole.SUPER_ADMIN)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                        "未找到超级管理员，无法确定评测题库归属"));
    }

    /**
     * 一次门禁判定结果。
     *
     * @param passed 是否放行
     * @param suite 评测集合
     * @param owner 题库归属账号
     * @param total 题数
     * @param passedCases 通过题数
     * @param passRate 通过率
     * @param recallAt5 Recall@5
     * @param mrr MRR
     * @param unsupportedAnswerRate 无依据回答率
     * @param runId 运行标识
     * @param failures 未达标项说明
     * @param failedCases 未通过题目明细
     */
    public record GateResult(boolean passed, String suite, String owner, int total, int passedCases,
                             double passRate, double recallAt5, double mrr, double unsupportedAnswerRate,
                             String runId, List<String> failures, List<GateFailure> failedCases) {
        public GateResult {
            failures = failures == null ? List.of() : List.copyOf(failures);
            failedCases = failedCases == null ? List.of() : List.copyOf(failedCases);
        }
    }

    /**
     * 单道未通过题目的诊断信息。
     *
     * @param id 题目标识
     * @param question 题干
     * @param score 最终得分
     * @param reason 判定原因
     * @param expectedSources 期望来源
     * @param actualSources 实际命中来源
     * @param missingKeywords 缺失的关键词
     */
    public record GateFailure(String id, String question, double score, String reason,
                              List<String> expectedSources, List<String> actualSources,
                              List<String> missingKeywords) {
        public GateFailure {
            expectedSources = expectedSources == null ? List.of() : List.copyOf(expectedSources);
            actualSources = actualSources == null ? List.of() : List.copyOf(actualSources);
            missingKeywords = missingKeywords == null ? List.of() : List.copyOf(missingKeywords);
        }
    }
}
