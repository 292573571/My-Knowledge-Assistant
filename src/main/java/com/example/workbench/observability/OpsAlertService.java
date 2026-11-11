package com.example.workbench.observability;

import com.example.workbench.logview.SystemLogRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 运维巡检：把已经采集到的 token 与前端错误数据接上出口。
 *
 * <p>此前 {@link ModelUsageRecorder} 与 {@code ClientErrorController} 只负责写库，
 * 没有任何消费者——出问题时数据在表里但没人看。这里每天扫一次最近窗口，
 * 命中阈值就写 WARN 并调用 {@link AlertNotifier}（默认邮件）。</p>
 *
 * <p>判定口径写死在这里，改阈值改配置即可，不要改逻辑：
 * 失败率 = 失败调用数 / 总调用数，样本量不足 {@code min-samples} 时不判失败率，
 * 避免一次偶发失败就报警。</p>
 */
@Component
public class OpsAlertService {

    private static final Logger log = LoggerFactory.getLogger(OpsAlertService.class);
    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.of("Asia/Shanghai"));

    /** ClientErrorController 的 logger 名，用于把前端上报从其它 ERROR 里摘出来。 */
    static final String CLIENT_ERROR_LOGGER = "com.example.workbench.observability.ClientErrorController";

    private final ModelUsageEventRepository usageRepository;
    private final SystemLogRepository systemLogRepository;
    private final AlertNotifier notifier;
    private final boolean enabled;
    private final int windowHours;
    private final long minSamples;
    private final double failureRateThreshold;
    private final long clientErrorThreshold;

    public OpsAlertService(ModelUsageEventRepository usageRepository,
                           SystemLogRepository systemLogRepository,
                           AlertNotifier notifier,
                           @Value("${workbench.alerts.enabled:true}") boolean enabled,
                           @Value("${workbench.alerts.window-hours:24}") int windowHours,
                           @Value("${workbench.alerts.min-samples:10}") long minSamples,
                           @Value("${workbench.alerts.failure-rate:0.2}") double failureRateThreshold,
                           @Value("${workbench.alerts.client-error-threshold:20}") long clientErrorThreshold) {
        this.usageRepository = usageRepository;
        this.systemLogRepository = systemLogRepository;
        this.notifier = notifier;
        this.enabled = enabled;
        this.windowHours = Math.max(1, windowHours);
        this.minSamples = Math.max(1, minSamples);
        this.failureRateThreshold = failureRateThreshold;
        this.clientErrorThreshold = clientErrorThreshold;
    }

    @Scheduled(cron = "${workbench.alerts.cron:0 30 8 * * *}", zone = "Asia/Shanghai")
    public void dailyCheck() {
        if (!enabled) {
            return;
        }
        try {
            AlertReport report = inspect(Instant.now().minus(Duration.ofHours(windowHours)));
            if (report.hasViolation()) {
                String body = format(report);
                log.warn("运维巡检命中告警：{}", report.violations());
                notifier.send("【识海】运维告警（" + report.violations().size() + " 项）", body);
            } else {
                log.info("运维巡检正常 calls={} failures={} clientErrors={}",
                        report.modelCalls(), report.modelFailures(), report.clientErrors());
            }
        } catch (RuntimeException exception) {
            // 巡检自身出错不能影响定时任务线程，也不能中断后续调度
            log.warn("运维巡检执行失败 reason={}", exception.toString());
        }
    }

    /**
     * 以 {@code since} 为起点统计并给出判定结果。抽成公开方法便于测试直接调用，
     * 不必等到 cron 触发。
     */
    public AlertReport inspect(Instant since) {
        ModelUsageEventRepository.UsageTotals totals = usageRepository.totalsAll(since);
        long calls = totals == null ? 0 : totals.getCalls();
        long success = totals == null ? 0 : totals.getSuccessCalls();
        long failures = Math.max(0, calls - success);
        double failureRate = calls == 0 ? 0d : (double) failures / calls;

        long clientErrors = systemLogRepository
                .countByLoggerAndLevelAndTimestampAfter(CLIENT_ERROR_LOGGER, "ERROR", since);
        long totalErrors = systemLogRepository.countByLevelAndTimestampAfter("ERROR", since);

        List<String> violations = new ArrayList<>();
        if (calls >= minSamples && failureRate >= failureRateThreshold) {
            violations.add(String.format("模型调用失败率 %.1f%%（%d/%d），阈值 %.0f%%",
                    failureRate * 100, failures, calls, failureRateThreshold * 100));
        }
        if (clientErrors >= clientErrorThreshold) {
            violations.add(String.format("前端错误上报 %d 条，阈值 %d 条", clientErrors, clientErrorThreshold));
        }
        return new AlertReport(windowHours, calls, failures, failureRate, clientErrors, totalErrors, List.copyOf(violations));
    }

    private String format(AlertReport report) {
        StringBuilder builder = new StringBuilder();
        builder.append("巡检窗口：最近 ").append(report.windowHours()).append(" 小时\n");
        builder.append("模型调用：").append(report.modelCalls()).append(" 次，失败 ")
                .append(report.modelFailures()).append(" 次，失败率 ")
                .append(String.format("%.1f%%", report.failureRate() * 100)).append('\n');
        builder.append("前端错误：").append(report.clientErrors()).append(" 条（同期 ERROR 日志共 ")
                .append(report.totalErrors()).append(" 条）\n");
        builder.append("\n命中项：\n");
        for (String violation : report.violations()) {
            builder.append("- ").append(violation).append('\n');
        }
        builder.append("\n排查入口：/api/logs?level=ERROR、/api/usage、/api/client-errors\n");
        builder.append("生成时间：").append(TIME_FORMAT.format(Instant.now()));
        return builder.toString();
    }

    /**
     * 一次巡检的结果快照。
     */
    public record AlertReport(
            int windowHours,
            long modelCalls,
            long modelFailures,
            double failureRate,
            long clientErrors,
            long totalErrors,
            List<String> violations
    ) {
        public boolean hasViolation() {
            return !violations.isEmpty();
        }
    }
}
