package com.example.workbench.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.workbench.auth.AppUser;
import com.example.workbench.auth.AppUserRepository;
import com.example.workbench.auth.SystemRole;
import com.example.workbench.modelconfig.ModelConfigContext;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class EvalGateServiceTest {

    @Mock
    private EvalCaseService evalCaseService;
    @Mock
    private EvalRunner evalRunner;
    @Mock
    private EvalExecutionGuard executionGuard;
    @Mock
    private AppUserRepository userRepository;

    private ModelConfigContext modelConfigContext;
    private AppUser admin;

    @BeforeEach
    void setUp() {
        modelConfigContext = new ModelConfigContext();
        admin = new AppUser("admin", "管理员", "hash");
        ReflectionTestUtils.setField(admin, "id", 7L);
    }

    private EvalGateService service(boolean enabled, String token, String ownerAccount) {
        return new EvalGateService(evalCaseService, evalRunner, executionGuard, userRepository,
                modelConfigContext, enabled, token, ownerAccount, "REGRESSION");
    }

    @Test
    void rejectsWrongOrMissingToken() {
        EvalGateService service = service(true, "secret-token", "");

        assertThat(service.authorized(null)).isFalse();
        assertThat(service.authorized("")).isFalse();
        assertThat(service.authorized("wrong")).isFalse();
        assertThat(service.authorized("secret-token")).isTrue();
    }

    @Test
    void rejectsEverythingWhenTokenNotConfigured() {
        EvalGateService service = service(true, "", "");

        assertThat(service.authorized("")).isFalse();
        assertThat(service.authorized("anything")).isFalse();
    }

    @Test
    void disabledGateReturnsServiceUnavailable() throws Exception {
        EvalGateService service = service(false, "secret-token", "");

        assertThatThrownBy(() -> service.run(null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("未启用");
        verify(evalRunner, never()).run(anyList(), anyBoolean(), any());
    }

    @Test
    void emptySuiteFailsClosed() throws Exception {
        EvalGateService service = service(true, "secret-token", "admin");
        when(userRepository.findByAccount("admin")).thenReturn(Optional.of(admin));
        when(evalCaseService.selectedCases(admin, null, "REGRESSION", null)).thenReturn(List.of());

        EvalGateService.GateResult result = service.run(null);

        assertThat(result.passed()).isFalse();
        assertThat(result.total()).isZero();
        assertThat(result.failures()).anyMatch(item -> item.contains("没有可用题目"));
        verify(evalRunner, never()).run(anyList(), anyBoolean(), any());
    }

    @Test
    void passingSummaryIsForwarded() throws Exception {
        EvalGateService service = service(true, "secret-token", "admin");
        List<EvalCase> cases = List.of(caseOf("case-1"));
        EvalSummary summary = new EvalSummary("run-1", 10, 9, 1, 0.9, 0.9, 0.9, 0.8,
                0.0, 0.0, 1.0, 10, 0.9, 0.8, 0.85, 0.88, true, true, List.of(), List.of(), List.of());
        when(userRepository.findByAccount("admin")).thenReturn(Optional.of(admin));
        when(evalCaseService.selectedCases(admin, null, "REGRESSION", null)).thenReturn(cases);
        when(executionGuard.execute(eq(admin), eq(1), any())).thenAnswer(invocation ->
                ((java.util.function.Supplier<?>) invocation.getArgument(2)).get());
        when(evalRunner.run(cases, false, admin)).thenReturn(summary);

        EvalGateService.GateResult result = service.run("REGRESSION");

        assertThat(result.passed()).isTrue();
        assertThat(result.passRate()).isEqualTo(0.9);
        assertThat(result.runId()).isEqualTo("run-1");
        assertThat(modelConfigContext.get()).isNull();
    }

    @Test
    void failingSummaryBlocksRelease() throws Exception {
        EvalGateService service = service(true, "secret-token", "");
        List<EvalCase> cases = List.of(caseOf("case-1"));
        EvalSummary summary = new EvalSummary("run-2", 10, 5, 5, 0.5, 0.5, 0.5, 0.4,
                0.2, 0.0, 1.0, 10, 0.5, 0.4, 0.45, 0.48, true, false,
                List.of("passRate 0.500 < 0.800"), List.of(), List.of());
        when(userRepository.findFirstBySystemRole(SystemRole.SUPER_ADMIN)).thenReturn(Optional.of(admin));
        when(evalCaseService.selectedCases(admin, null, "REGRESSION", null)).thenReturn(cases);
        when(executionGuard.execute(eq(admin), eq(1), any())).thenAnswer(invocation ->
                ((java.util.function.Supplier<?>) invocation.getArgument(2)).get());
        when(evalRunner.run(cases, false, admin)).thenReturn(summary);

        EvalGateService.GateResult result = service.run(null);

        assertThat(result.passed()).isFalse();
        assertThat(result.failures()).containsExactly("passRate 0.500 < 0.800");
        assertThat(modelConfigContext.get()).isNull();
    }

    @Test
    void failingSummaryListsFailedCasesForDeployLog() throws Exception {
        // 门禁返回里要带上是哪道题没过，发版日志才能直接定位退化题。
        EvalGateService service = service(true, "secret-token", "");
        List<EvalCase> cases = List.of(caseOf("case-1"));
        EvalResult failed = new EvalResult("draft-003", "rag", "fact", "泰山平台怎么配置数据源",
                false, true, false, false, 0.4, null, true, 0.4, "来源不匹配", "Judge disabled", "答案",
                List.of("泰山数据迁移平台操作手册.doc"), List.of("史上最全的Java面试题独家整理(2).pdf"),
                List.of(), List.of(), List.of(), List.of("数据源管理"), true, false, 0.25, false, false, false,
                List.of(), EvalSuite.SMOKE, EvalLayer.RETRIEVAL, true, 0.0, 0.0, 0.0, 0.0, null);
        EvalSummary summary = new EvalSummary("run-3", 6, 4, 2, 0.6667, 0.6, 0.5, 0.5,
                0.3333, 0.0, 1.0, 6, 1.0, 0.5, 0.7222, 0.6, true, false,
                List.of("passRate 0.667 < 0.800"), List.of(), List.of(failed));
        when(userRepository.findFirstBySystemRole(SystemRole.SUPER_ADMIN)).thenReturn(Optional.of(admin));
        when(evalCaseService.selectedCases(admin, null, "REGRESSION", null)).thenReturn(cases);
        when(executionGuard.execute(eq(admin), eq(1), any())).thenAnswer(invocation ->
                ((java.util.function.Supplier<?>) invocation.getArgument(2)).get());
        when(evalRunner.run(cases, false, admin)).thenReturn(summary);

        EvalGateService.GateResult result = service.run(null);

        assertThat(result.failedCases()).hasSize(1);
        EvalGateService.GateFailure failure = result.failedCases().get(0);
        assertThat(failure.id()).isEqualTo("draft-003");
        assertThat(failure.question()).contains("泰山");
        assertThat(failure.actualSources()).containsExactly("史上最全的Java面试题独家整理(2).pdf");
        assertThat(failure.missingKeywords()).containsExactly("数据源管理");
        assertThat(failure.reason()).isEqualTo("来源不匹配");
        assertThat(failure.answer()).isEqualTo("答案");
    }

    @Test
    void failedCaseAnswerIsTruncatedForLogs() throws Exception {
        // 长答案只保留前 200 字，避免门禁 JSON 撑爆发版日志。
        EvalGateService service = service(true, "secret-token", "");
        List<EvalCase> cases = List.of(caseOf("case-1"));
        String longAnswer = "答".repeat(500);
        EvalResult failed = new EvalResult("draft-038", "rag", "fact", "问题",
                false, true, false, false, 0.2, null, true, 0.2, "缺少本地依据", "Judge disabled", longAnswer,
                List.of("doc.doc"), List.of(), List.of(), List.of(), List.of(), List.of("表类型维护"),
                false, false, 0.0, true, false, false, List.of(), EvalSuite.SMOKE, EvalLayer.RETRIEVAL,
                true, 0.0, 0.0, 0.0, 0.0, null);
        EvalSummary summary = new EvalSummary("run-4", 6, 5, 1, 0.8333, 0.6, 0.5, 0.5,
                0.1667, 0.0, 1.0, 6, 1.0, 0.5, 0.7222, 0.6, true, false,
                List.of("unsupportedAnswerRate 0.167 > 0.050"), List.of(), List.of(failed));
        when(userRepository.findFirstBySystemRole(SystemRole.SUPER_ADMIN)).thenReturn(Optional.of(admin));
        when(evalCaseService.selectedCases(admin, null, "REGRESSION", null)).thenReturn(cases);
        when(executionGuard.execute(eq(admin), eq(1), any())).thenAnswer(invocation ->
                ((java.util.function.Supplier<?>) invocation.getArgument(2)).get());
        when(evalRunner.run(cases, false, admin)).thenReturn(summary);

        EvalGateService.GateFailure failure = service.run(null).failedCases().get(0);

        assertThat(failure.answer()).hasSize(201).endsWith("…");
        assertThat(failure.actualSources()).isEmpty();
    }

    private EvalCase caseOf(String caseId) {
        return new EvalCase(caseId, "rag", "fact", "问题", false, true, false,
                List.of(), List.of(), List.of(), List.of());
    }
}
