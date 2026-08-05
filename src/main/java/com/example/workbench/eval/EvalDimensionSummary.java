package com.example.workbench.eval;

/**
 * 按评测集和评测层级聚合的一组运行指标。
 *
 * @param suite 评测集合
 * @param layer 评测层级
 * @param total 用例数
 * @param passed 通过数
 * @param passRate 通过率
 * @param rankingCaseCount 排名指标适用用例数
 * @param recallAt5 Recall@5
 * @param precisionAt5 Precision@5
 * @param mrr 平均倒数排名
 * @param ndcgAt5 NDCG@5
 */
public record EvalDimensionSummary(
        EvalSuite suite,
        EvalLayer layer,
        int total,
        int passed,
        double passRate,
        int rankingCaseCount,
        double recallAt5,
        double precisionAt5,
        double mrr,
        double ndcgAt5
) {
}
