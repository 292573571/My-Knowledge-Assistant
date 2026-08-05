package com.example.workbench.eval;

import com.example.workbench.rag.RetrievalDebug;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 基于文档级相关性计算检索排名指标。
 */
@Component
public class EvalRetrievalMetrics {

    private static final int K = 5;

    /**
     * 计算单条评测用例的文档级 Top-5 指标。
     *
     * @param evalCase 评测用例
     * @param candidates 按最终排名返回的检索候选
     * @return 单条用例的检索排名指标
     */
    public Metrics calculate(EvalCase evalCase, List<RetrievalDebug> candidates) {
        Set<String> expected = normalizedExpectedSources(evalCase.expectedSources());
        if (evalCase.expectNoAnswer() || expected.isEmpty()) {
            return Metrics.notApplicable();
        }

        List<String> rankedDocuments = rankedDocuments(candidates);
        int relevantRetrieved = 0;
        int firstRelevantRank = 0;
        double dcg = 0;
        for (int index = 0; index < Math.min(K, rankedDocuments.size()); index++) {
            if (!isRelevant(rankedDocuments.get(index), expected)) {
                continue;
            }
            relevantRetrieved++;
            int rank = index + 1;
            if (firstRelevantRank == 0) {
                firstRelevantRank = rank;
            }
            dcg += 1 / log2(rank + 1);
        }

        double idealDcg = 0;
        for (int rank = 1; rank <= Math.min(K, expected.size()); rank++) {
            idealDcg += 1 / log2(rank + 1);
        }
        return new Metrics(
                true,
                (double) relevantRetrieved / expected.size(),
                (double) relevantRetrieved / K,
                firstRelevantRank == 0 ? 0 : 1.0 / firstRelevantRank,
                idealDcg == 0 ? 0 : dcg / idealDcg,
                firstRelevantRank == 0 ? null : firstRelevantRank
        );
    }

    private List<String> rankedDocuments(List<RetrievalDebug> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        Map<String, String> distinct = new LinkedHashMap<>();
        candidates.stream()
                .sorted(Comparator.comparing(
                        item -> item.finalRank() == null ? Integer.MAX_VALUE : item.finalRank()))
                .filter(item -> item.fileName() != null && !item.fileName().isBlank())
                .forEach(item -> distinct.putIfAbsent(normalize(item.fileName()), item.fileName()));
        return List.copyOf(distinct.values());
    }

    private Set<String> normalizedExpectedSources(List<String> sources) {
        if (sources == null) {
            return Set.of();
        }
        Set<String> normalized = new LinkedHashSet<>();
        sources.stream()
                .filter(source -> source != null && !source.isBlank())
                .map(this::normalize)
                .forEach(normalized::add);
        return normalized;
    }

    private boolean isRelevant(String actual, Set<String> expected) {
        String normalizedActual = normalize(actual);
        return expected.stream().anyMatch(source -> normalizedActual.contains(source) || source.contains(normalizedActual));
    }

    private String normalize(String value) {
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private double log2(double value) {
        return Math.log(value) / Math.log(2);
    }

    /**
     * 单条用例的文档级检索排名指标。
     *
     * @param applicable 是否适用于排名评测
     * @param recallAt5 Recall@5
     * @param precisionAt5 Precision@5
     * @param reciprocalRank 倒数排名
     * @param ndcgAt5 NDCG@5
     * @param firstRelevantRank 首个相关文档排名
     */
    public record Metrics(
            boolean applicable,
            double recallAt5,
            double precisionAt5,
            double reciprocalRank,
            double ndcgAt5,
            Integer firstRelevantRank
    ) {
        static Metrics notApplicable() {
            return new Metrics(false, 0, 0, 0, 0, null);
        }
    }
}
