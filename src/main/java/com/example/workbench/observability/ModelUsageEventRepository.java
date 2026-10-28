package com.example.workbench.observability;

import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ModelUsageEventRepository extends JpaRepository<ModelUsageEvent, Long> {

    interface UsageTotals {
        long getCalls();

        long getSuccessCalls();

        long getPromptTokens();

        long getCompletionTokens();

        long getTotalDurationMs();
    }

    interface DailyUsage {
        java.time.LocalDate getDay();

        long getCalls();

        long getPromptTokens();

        long getCompletionTokens();
    }

    interface ModelUsage {
        String getModel();

        long getCalls();

        long getPromptTokens();

        long getCompletionTokens();

        double getAvgDurationMs();
    }

    interface RetrievalStats {
        long getAssistantMessages();

        long getAnswersWithSources();
    }

    @Query("""
            select count(m) as calls,
                   sum(case when m.success = true then 1 else 0 end) as successCalls,
                   coalesce(sum(m.promptTokens), 0) as promptTokens,
                   coalesce(sum(m.completionTokens), 0) as completionTokens,
                   coalesce(sum(m.durationMs), 0) as totalDurationMs
            from ModelUsageEvent m
            where m.userId = :userId and m.createdAt >= :since
            """)
    UsageTotals totals(@Param("userId") Long userId, @Param("since") Instant since);

    @Query(value = """
            select (created_at at time zone 'Asia/Shanghai')::date as day,
                   count(*) as calls,
                   coalesce(sum(prompt_tokens), 0) as prompt_tokens,
                   coalesce(sum(completion_tokens), 0) as completion_tokens
            from model_usage_events
            where user_id = :userId and created_at >= :since
            group by 1
            order by 1
            """, nativeQuery = true)
    List<DailyUsage> dailyUsage(@Param("userId") Long userId, @Param("since") Instant since);

    @Query("""
            select m.model as model,
                   count(m) as calls,
                   coalesce(sum(m.promptTokens), 0) as promptTokens,
                   coalesce(sum(m.completionTokens), 0) as completionTokens,
                   coalesce(avg(m.durationMs), 0) as avgDurationMs
            from ModelUsageEvent m
            where m.userId = :userId and m.createdAt >= :since
            group by m.model
            order by count(m) desc
            """)
    List<ModelUsage> byModel(@Param("userId") Long userId, @Param("since") Instant since);

    @Query(value = """
            select count(*) filter (where m.role = 'assistant') as assistant_messages,
                   count(*) filter (where m.role = 'assistant'
                       and m.sources_json is not null
                       and m.sources_json <> ''
                       and m.sources_json <> '[]') as answers_with_sources
            from chat_messages m
            join chat_conversations c on m.conversation_id = c.id
            where c.user_id = :userId and m.created_at >= :since
            """, nativeQuery = true)
    RetrievalStats retrievalStats(@Param("userId") Long userId, @Param("since") Instant since);
}
