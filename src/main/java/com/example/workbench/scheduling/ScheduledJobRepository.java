package com.example.workbench.scheduling;

import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface ScheduledJobRepository extends JpaRepository<ScheduledJobEntity, String> {

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query(value = """
            update scheduled_jobs
               set lease_owner = :workerId,
                   lease_expires_at = :leaseExpiresAt,
                   last_started_at = :now,
                   next_run_at = :now + make_interval(secs => interval_seconds)
             where job_key = :jobKey
               and enabled = true
               and next_run_at <= :now
               and (lease_expires_at is null or lease_expires_at <= :now)
            """, nativeQuery = true)
    int claim(@Param("jobKey") String jobKey,
              @Param("workerId") String workerId,
              @Param("now") Instant now,
              @Param("leaseExpiresAt") Instant leaseExpiresAt);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query(value = """
            update scheduled_jobs
               set lease_owner = null,
                   lease_expires_at = null,
                   last_finished_at = :now,
                   last_error = :lastError,
                   failure_count = case when :lastError is null then 0 else failure_count + 1 end
             where job_key = :jobKey
               and lease_owner = :workerId
            """, nativeQuery = true)
    int finish(@Param("jobKey") String jobKey,
               @Param("workerId") String workerId,
               @Param("now") Instant now,
               @Param("lastError") String lastError);
}
