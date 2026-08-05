package com.example.workbench.eval;

import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 根据稳定的汇总阈值判定一次离线评测是否可以通过质量门禁。
 */
@Component
public class EvalQualityGate {

    private final boolean enabled;
    private final double minimumPassRate;
    private final double minimumRecallAt5;
    private final double minimumMrr;
    private final double maximumUnsupportedAnswerRate;

    /**
     * 创建可配置的离线质量门禁。
     *
     * @param enabled 是否启用门禁
     * @param minimumPassRate 最低总体通过率
     * @param minimumRecallAt5 最低 Recall@5
     * @param minimumMrr 最低 MRR
     * @param maximumUnsupportedAnswerRate 最高无依据回答率
     */
    public EvalQualityGate(
            @Value("${workbench.eval.gate.enabled:false}") boolean enabled,
            @Value("${workbench.eval.gate.min-pass-rate:0.8}") double minimumPassRate,
            @Value("${workbench.eval.gate.min-recall-at-5:0.8}") double minimumRecallAt5,
            @Value("${workbench.eval.gate.min-mrr:0.7}") double minimumMrr,
            @Value("${workbench.eval.gate.max-unsupported-answer-rate:0.05}") double maximumUnsupportedAnswerRate
    ) {
        this.enabled = enabled;
        this.minimumPassRate = minimumPassRate;
        this.minimumRecallAt5 = minimumRecallAt5;
        this.minimumMrr = minimumMrr;
        this.maximumUnsupportedAnswerRate = maximumUnsupportedAnswerRate;
    }

    /**
     * 判定汇总指标，未开启门禁时始终通过。
     *
     * @param passRate 总体通过率
     * @param rankingCaseCount 排名指标适用用例数
     * @param recallAt5 Recall@5
     * @param mrr 平均倒数排名
     * @param unsupportedAnswerRate 无依据回答率
     * @return 门禁判定结果
     */
    public Result evaluate(double passRate, int rankingCaseCount, double recallAt5, double mrr,
                           double unsupportedAnswerRate) {
        if (!enabled) {
            return new Result(false, true, List.of());
        }
        List<String> failures = new ArrayList<>();
        if (passRate < minimumPassRate) {
            failures.add("passRate %.3f < %.3f".formatted(passRate, minimumPassRate));
        }
        if (rankingCaseCount > 0 && recallAt5 < minimumRecallAt5) {
            failures.add("recallAt5 %.3f < %.3f".formatted(recallAt5, minimumRecallAt5));
        }
        if (rankingCaseCount > 0 && mrr < minimumMrr) {
            failures.add("mrr %.3f < %.3f".formatted(mrr, minimumMrr));
        }
        if (unsupportedAnswerRate > maximumUnsupportedAnswerRate) {
            failures.add("unsupportedAnswerRate %.3f > %.3f"
                    .formatted(unsupportedAnswerRate, maximumUnsupportedAnswerRate));
        }
        return new Result(true, failures.isEmpty(), List.copyOf(failures));
    }

    /**
     * 一次质量门禁的判定结果。
     *
     * @param enabled 是否启用门禁
     * @param passed 是否通过门禁
     * @param failures 未通过的指标说明
     */
    public record Result(boolean enabled, boolean passed, List<String> failures) {
    }
}
