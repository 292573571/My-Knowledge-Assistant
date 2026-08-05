package com.example.workbench.eval;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * 对评测结果进行 suite/layer 维度聚合。
 */
@Component
public class EvalDimensionSummarizer {

    /**
     * 聚合每个评测集合和层级组合的通过及检索排名指标。
     *
     * @param results 单题结果
     * @return 按集合和层级排序的聚合结果
     */
    public List<EvalDimensionSummary> summarize(List<EvalResult> results) {
        if (results == null || results.isEmpty()) {
            return List.of();
        }
        return results.stream()
                .collect(Collectors.groupingBy(result -> new Key(result.suite(), result.layer())))
                .entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.comparing(Key::suite).thenComparing(Key::layer)))
                .map(item -> summarize(item.getKey(), item.getValue()))
                .toList();
    }

    private EvalDimensionSummary summarize(Key key, List<EvalResult> results) {
        int passed = (int) results.stream().filter(EvalResult::passed).count();
        List<EvalResult> ranking = results.stream().filter(EvalResult::rankingMetricsApplicable).toList();
        return new EvalDimensionSummary(key.suite(), key.layer(), results.size(), passed,
                ratio(passed, results.size()), ranking.size(), average(ranking, EvalResult::recallAt5),
                average(ranking, EvalResult::precisionAt5), average(ranking, EvalResult::reciprocalRank),
                average(ranking, EvalResult::ndcgAt5));
    }

    private double average(List<EvalResult> results, java.util.function.ToDoubleFunction<EvalResult> value) {
        return results.stream().mapToDouble(value).average().orElse(0);
    }

    private double ratio(int numerator, int denominator) {
        return denominator == 0 ? 0 : (double) numerator / denominator;
    }

    private record Key(EvalSuite suite, EvalLayer layer) {
    }
}
