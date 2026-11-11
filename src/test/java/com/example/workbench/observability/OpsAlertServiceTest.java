package com.example.workbench.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.workbench.logview.SystemLogRepository;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class OpsAlertServiceTest {

    private ModelUsageEventRepository usageRepository;
    private SystemLogRepository systemLogRepository;
    private AlertNotifier notifier;
    private OpsAlertService service;

    private final Instant since = Instant.parse("2026-11-10T00:00:00Z");

    @BeforeEach
    void setUp() {
        usageRepository = mock(ModelUsageEventRepository.class);
        systemLogRepository = mock(SystemLogRepository.class);
        notifier = mock(AlertNotifier.class);
        service = new OpsAlertService(usageRepository, systemLogRepository, notifier,
                true, 24, 10, 0.2, 20);
    }

    @Test
    @DisplayName("样本量不足时不判定失败率，避免偶发失败误报")
    void doesNotJudgeFailureRateBelowMinSamples() {
        stubUsage(5, 0);
        stubClientErrors(0);

        OpsAlertService.AlertReport report = service.inspect(since);

        assertThat(report.modelCalls()).isEqualTo(5);
        assertThat(report.modelFailures()).isEqualTo(5);
        assertThat(report.failureRate()).isEqualTo(1.0);
        assertThat(report.violations()).isEmpty();
    }

    @Test
    @DisplayName("失败率达到阈值时给出告警项")
    void flagsHighFailureRate() {
        stubUsage(20, 14);
        stubClientErrors(0);

        OpsAlertService.AlertReport report = service.inspect(since);

        assertThat(report.failureRate()).isEqualTo(0.3);
        assertThat(report.violations()).hasSize(1);
        assertThat(report.violations().get(0)).contains("模型调用失败率").contains("6/20");
    }

    @Test
    @DisplayName("失败率未达阈值时不告警")
    void ignoresFailureRateUnderThreshold() {
        stubUsage(20, 19);
        stubClientErrors(0);

        assertThat(service.inspect(since).violations()).isEmpty();
    }

    @Test
    @DisplayName("前端错误条数达到阈值时单独告警")
    void flagsClientErrorFlood() {
        stubUsage(0, 0);
        stubClientErrors(25);

        OpsAlertService.AlertReport report = service.inspect(since);

        assertThat(report.clientErrors()).isEqualTo(25);
        assertThat(report.violations()).hasSize(1);
        assertThat(report.violations().get(0)).contains("前端错误上报 25 条");
    }

    @Test
    @DisplayName("两类问题同时命中时合并成一次通知，不重复发邮件")
    void sendsOneNotificationForCombinedViolations() {
        stubUsage(20, 10);
        stubClientErrors(30);

        service.dailyCheck();

        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(notifier).send(subject.capture(), body.capture());
        assertThat(subject.getValue()).contains("2 项");
        assertThat(body.getValue())
                .contains("模型调用失败率")
                .contains("前端错误上报 30 条")
                .contains("巡检窗口：最近 24 小时");
    }

    @Test
    @DisplayName("一切正常时不发通知")
    void staysSilentWhenHealthy() {
        stubUsage(120, 119);
        stubClientErrors(3);

        service.dailyCheck();

        verify(notifier, never()).send(anyString(), anyString());
    }

    @Test
    @DisplayName("统计异常时巡检自身不抛出，避免打断定时任务")
    void swallowsRepositoryFailures() {
        when(usageRepository.totalsAll(any())).thenThrow(new IllegalStateException("db down"));
        when(systemLogRepository.countByLoggerAndLevelAndTimestampAfter(anyString(), eq("ERROR"), any()))
                .thenReturn(0L);
        when(systemLogRepository.countByLevelAndTimestampAfter(anyString(), any())).thenReturn(0L);

        service.dailyCheck();

        verify(notifier, never()).send(anyString(), anyString());
    }

    private void stubUsage(long calls, long successCalls) {
        ModelUsageEventRepository.UsageTotals totals = mock(ModelUsageEventRepository.UsageTotals.class);
        when(totals.getCalls()).thenReturn(calls);
        when(totals.getSuccessCalls()).thenReturn(successCalls);
        when(usageRepository.totalsAll(any())).thenReturn(totals);
    }

    private void stubClientErrors(long count) {
        when(systemLogRepository.countByLoggerAndLevelAndTimestampAfter(
                eq(OpsAlertService.CLIENT_ERROR_LOGGER), eq("ERROR"), any())).thenReturn(count);
        when(systemLogRepository.countByLevelAndTimestampAfter(eq("ERROR"), any())).thenReturn(count);
    }
}
