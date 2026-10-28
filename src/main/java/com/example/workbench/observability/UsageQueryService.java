package com.example.workbench.observability;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UsageQueryService {

    private final ModelUsageEventRepository repository;

    public UsageQueryService(ModelUsageEventRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public UsageSummary summary(Long userId, int days) {
        int clamped = Math.max(1, Math.min(90, days));
        Instant since = Instant.now().minus(java.time.Duration.ofDays(clamped));
        ModelUsageEventRepository.UsageTotals totals = repository.totals(userId, since);
        ModelUsageEventRepository.RetrievalStats retrieval = repository.retrievalStats(userId, since);
        List<ModelUsageEventRepository.DailyUsage> daily = repository.dailyUsage(userId, since);
        List<ModelUsageEventRepository.ModelUsage> byModel = repository.byModel(userId, since);

        return new UsageSummary(
                clamped,
                new Totals(totals.getCalls(), totals.getSuccessCalls(),
                        totals.getCalls() - totals.getSuccessCalls(),
                        totals.getPromptTokens(), totals.getCompletionTokens(),
                        totals.getPromptTokens() + totals.getCompletionTokens(),
                        totals.getTotalDurationMs(),
                        totals.getCalls() == 0 ? 0 : totals.getTotalDurationMs() / totals.getCalls()),
                new Retrieval(retrieval.getAssistantMessages(), retrieval.getAnswersWithSources()),
                daily.stream()
                        .map(item -> new DailyPoint(item.getDay(), item.getCalls(),
                                item.getPromptTokens(), item.getCompletionTokens()))
                        .toList(),
                byModel.stream()
                        .map(item -> new ModelStat(item.getModel(), item.getCalls(),
                                item.getPromptTokens(), item.getCompletionTokens(),
                                Math.round(item.getAvgDurationMs())))
                        .toList());
    }

    public record UsageSummary(
            int days,
            Totals totals,
            Retrieval retrieval,
            List<DailyPoint> daily,
            List<ModelStat> models
    ) {
    }

    public record Totals(
            long calls,
            long successCalls,
            long failedCalls,
            long promptTokens,
            long completionTokens,
            long totalTokens,
            long totalDurationMs,
            long avgDurationMs
    ) {
    }

    public record Retrieval(long assistantMessages, long answersWithSources) {

        public double hitRate() {
            return assistantMessages == 0 ? 0 : (double) answersWithSources / assistantMessages;
        }
    }

    public record DailyPoint(LocalDate day, long calls, long promptTokens, long completionTokens) {
    }

    public record ModelStat(String model, long calls, long promptTokens, long completionTokens,
                            long avgDurationMs) {
    }
}
