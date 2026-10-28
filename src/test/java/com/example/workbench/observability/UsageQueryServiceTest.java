package com.example.workbench.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class UsageQueryServiceTest {

    private final ModelUsageEventRepository repository = mock(ModelUsageEventRepository.class);
    private final UsageQueryService service = new UsageQueryService(repository);

    @Test
    void assemblesSummaryFromRepositoryProjections() {
        Instant since = Instant.now().minus(java.time.Duration.ofDays(30));
        ModelUsageEventRepository.UsageTotals totals = mock(ModelUsageEventRepository.UsageTotals.class);
        when(totals.getCalls()).thenReturn(10L);
        when(totals.getSuccessCalls()).thenReturn(9L);
        when(totals.getPromptTokens()).thenReturn(1000L);
        when(totals.getCompletionTokens()).thenReturn(500L);
        when(totals.getTotalDurationMs()).thenReturn(20000L);
        when(repository.totals(org.mockito.ArgumentMatchers.eq(7L), org.mockito.ArgumentMatchers.any()))
                .thenReturn(totals);

        ModelUsageEventRepository.RetrievalStats retrieval =
                mock(ModelUsageEventRepository.RetrievalStats.class);
        when(retrieval.getAssistantMessages()).thenReturn(4L);
        when(retrieval.getAnswersWithSources()).thenReturn(3L);
        when(repository.retrievalStats(org.mockito.ArgumentMatchers.eq(7L), org.mockito.ArgumentMatchers.any()))
                .thenReturn(retrieval);

        ModelUsageEventRepository.DailyUsage daily =
                mock(ModelUsageEventRepository.DailyUsage.class);
        when(daily.getDay()).thenReturn(LocalDate.of(2026, 10, 28));
        when(daily.getCalls()).thenReturn(6L);
        when(daily.getPromptTokens()).thenReturn(600L);
        when(daily.getCompletionTokens()).thenReturn(300L);
        when(repository.dailyUsage(org.mockito.ArgumentMatchers.eq(7L), org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(daily));

        ModelUsageEventRepository.ModelUsage model =
                mock(ModelUsageEventRepository.ModelUsage.class);
        when(model.getModel()).thenReturn("deepseek-ai/DeepSeek-V4-Flash");
        when(model.getCalls()).thenReturn(10L);
        when(model.getPromptTokens()).thenReturn(1000L);
        when(model.getCompletionTokens()).thenReturn(500L);
        when(model.getAvgDurationMs()).thenReturn(2000.4);
        when(repository.byModel(org.mockito.ArgumentMatchers.eq(7L), org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(model));

        UsageQueryService.UsageSummary summary = service.summary(7L, 30);

        assertThat(summary.days()).isEqualTo(30);
        assertThat(summary.totals().calls()).isEqualTo(10);
        assertThat(summary.totals().failedCalls()).isEqualTo(1);
        assertThat(summary.totals().totalTokens()).isEqualTo(1500);
        assertThat(summary.totals().avgDurationMs()).isEqualTo(2000);
        assertThat(summary.retrieval().assistantMessages()).isEqualTo(4);
        assertThat(summary.retrieval().hitRate()).isEqualTo(0.75);
        assertThat(summary.daily()).hasSize(1);
        assertThat(summary.daily().get(0).day()).isEqualTo(LocalDate.of(2026, 10, 28));
        assertThat(summary.models()).hasSize(1);
        assertThat(summary.models().get(0).avgDurationMs()).isEqualTo(2000);
    }

    @Test
    void clampsDaysRange() {
        ModelUsageEventRepository.UsageTotals totals = mock(ModelUsageEventRepository.UsageTotals.class);
        when(totals.getCalls()).thenReturn(0L);
        when(totals.getSuccessCalls()).thenReturn(0L);
        when(totals.getPromptTokens()).thenReturn(0L);
        when(totals.getCompletionTokens()).thenReturn(0L);
        when(totals.getTotalDurationMs()).thenReturn(0L);
        when(repository.totals(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(totals);
        ModelUsageEventRepository.RetrievalStats retrieval =
                mock(ModelUsageEventRepository.RetrievalStats.class);
        when(retrieval.getAssistantMessages()).thenReturn(0L);
        when(retrieval.getAnswersWithSources()).thenReturn(0L);
        when(repository.retrievalStats(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(retrieval);
        when(repository.dailyUsage(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of());
        when(repository.byModel(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of());

        assertThat(service.summary(1L, 500).days()).isEqualTo(90);
        assertThat(service.summary(1L, 0).days()).isEqualTo(1);
    }
}
