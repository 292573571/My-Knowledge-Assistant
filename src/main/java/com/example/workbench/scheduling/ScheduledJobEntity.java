package com.example.workbench.scheduling;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import org.hibernate.annotations.Comment;

@Entity
@Table(name = "scheduled_jobs")
@Comment("数据库驱动的定时任务表")
public class ScheduledJobEntity {

    @Id
    @Column(name = "job_key", length = 100)
    @Comment("任务业务标识")
    private String jobKey;

    @Column(nullable = false)
    @Comment("任务是否启用")
    private boolean enabled;

    @Column(name = "next_run_at", nullable = false)
    @Comment("下一次执行时间")
    private Instant nextRunAt;

    @Column(name = "interval_seconds", nullable = false)
    @Comment("执行间隔秒数")
    private long intervalSeconds;

    @Column(name = "lease_owner", length = 100)
    @Comment("当前租约持有实例")
    private String leaseOwner;

    @Column(name = "lease_expires_at")
    @Comment("当前租约过期时间")
    private Instant leaseExpiresAt;

    @Column(name = "last_started_at")
    @Comment("最近开始执行时间")
    private Instant lastStartedAt;

    @Column(name = "last_finished_at")
    @Comment("最近完成执行时间")
    private Instant lastFinishedAt;

    @Column(name = "last_error", length = 1000)
    @Comment("最近一次失败信息")
    private String lastError;

    @Column(name = "failure_count", nullable = false)
    @Comment("连续失败次数")
    private int failureCount;

    @Version
    @Comment("乐观锁版本")
    private long version;

    protected ScheduledJobEntity() {
    }

    public String getJobKey() {
        return jobKey;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public Instant getNextRunAt() {
        return nextRunAt;
    }

    public long getIntervalSeconds() {
        return intervalSeconds;
    }

    public String getLeaseOwner() {
        return leaseOwner;
    }

    public Instant getLeaseExpiresAt() {
        return leaseExpiresAt;
    }

    public Instant getLastStartedAt() {
        return lastStartedAt;
    }

    public Instant getLastFinishedAt() {
        return lastFinishedAt;
    }

    public String getLastError() {
        return lastError;
    }

    public int getFailureCount() {
        return failureCount;
    }
}
