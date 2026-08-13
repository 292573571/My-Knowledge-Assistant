package com.example.workbench.scheduling;

import com.example.workbench.agent.MaintenancePendingActionCleanupService;
import com.example.workbench.agent.TeachingAttemptCleanupService;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
class DatabaseScheduledJobRunner {

    static final String TEACHING_ATTEMPT_CLEANUP = "teaching-attempt-cleanup";
    static final String MAINTENANCE_ACTION_CLEANUP = "maintenance-action-cleanup";
    private static final Logger log = LoggerFactory.getLogger(DatabaseScheduledJobRunner.class);
    private static final long LEASE_SECONDS = 60;

    private final ScheduledJobRepository jobRepository;
    private final TeachingAttemptCleanupService teachingAttemptCleanupService;
    private final MaintenancePendingActionCleanupService maintenanceActionCleanupService;
    private final String workerId = UUID.randomUUID().toString();

    DatabaseScheduledJobRunner(ScheduledJobRepository jobRepository,
                               TeachingAttemptCleanupService teachingAttemptCleanupService,
                               MaintenancePendingActionCleanupService maintenanceActionCleanupService) {
        this.jobRepository = jobRepository;
        this.teachingAttemptCleanupService = teachingAttemptCleanupService;
        this.maintenanceActionCleanupService = maintenanceActionCleanupService;
    }

    // 这里只负责高频唤醒；任务是否启用、何时执行和多实例抢占由 scheduled_jobs 控制。
    @Scheduled(fixedDelay = 1000, initialDelay = 1000)
    void poll() {
        runIfDue(TEACHING_ATTEMPT_CLEANUP, () -> teachingAttemptCleanupService.cleanupExpired(Instant.now()));
        runIfDue(MAINTENANCE_ACTION_CLEANUP, () -> maintenanceActionCleanupService.cleanupExpired(Instant.now()));
    }

    private void runIfDue(String jobKey, Runnable task) {
        Instant now = Instant.now();
        // Claim 时把 nextRunAt 推进到下一个周期，租约过期后不会在高频唤醒期间重复执行。
        int claimed = jobRepository.claim(jobKey, workerId, now, now.plusSeconds(LEASE_SECONDS));
        if (claimed == 0) return;

        String error = null;
        try {
            task.run();
        } catch (RuntimeException exception) {
            error = exception.getClass().getSimpleName();
            log.error("数据库定时任务执行失败 jobKey={} errorType={}", jobKey, error);
        } finally {
            jobRepository.finish(jobKey, workerId, Instant.now(), error);
        }
    }
}
