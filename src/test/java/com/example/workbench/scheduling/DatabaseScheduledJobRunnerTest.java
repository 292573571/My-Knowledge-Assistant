package com.example.workbench.scheduling;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.workbench.agent.MaintenancePendingActionCleanupService;
import com.example.workbench.agent.TeachingAttemptCleanupService;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class DatabaseScheduledJobRunnerTest {

    @Test
    void runsOnlyWhenTheDatabaseClaimsTheJob() {
        ScheduledJobRepository repository = mock(ScheduledJobRepository.class);
        TeachingAttemptCleanupService teaching = mock(TeachingAttemptCleanupService.class);
        MaintenancePendingActionCleanupService maintenance = mock(MaintenancePendingActionCleanupService.class);
        DatabaseScheduledJobRunner runner = new DatabaseScheduledJobRunner(repository, teaching, maintenance);
        when(repository.claim(eq(DatabaseScheduledJobRunner.TEACHING_ATTEMPT_CLEANUP), any(), any(), any())).thenReturn(1);
        when(repository.claim(eq(DatabaseScheduledJobRunner.MAINTENANCE_ACTION_CLEANUP), any(), any(), any())).thenReturn(0);

        runner.poll();

        verify(teaching).cleanupExpired(any(Instant.class));
        verify(maintenance, never()).cleanupExpired(any(Instant.class));
        verify(repository).finish(eq(DatabaseScheduledJobRunner.TEACHING_ATTEMPT_CLEANUP), any(), any(), eq(null));
    }

    @Test
    void recordsFailureAndReleasesTheDatabaseLease() {
        ScheduledJobRepository repository = mock(ScheduledJobRepository.class);
        TeachingAttemptCleanupService teaching = mock(TeachingAttemptCleanupService.class);
        MaintenancePendingActionCleanupService maintenance = mock(MaintenancePendingActionCleanupService.class);
        DatabaseScheduledJobRunner runner = new DatabaseScheduledJobRunner(repository, teaching, maintenance);
        when(repository.claim(any(), any(), any(), any())).thenReturn(1);
        org.mockito.Mockito.doThrow(new IllegalStateException("database down"))
                .when(teaching).cleanupExpired(any(Instant.class));

        runner.poll();

        verify(repository).finish(eq(DatabaseScheduledJobRunner.TEACHING_ATTEMPT_CLEANUP), any(), any(),
                eq("IllegalStateException"));
    }
}
