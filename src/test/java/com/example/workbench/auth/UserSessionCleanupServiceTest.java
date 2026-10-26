package com.example.workbench.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class UserSessionCleanupServiceTest {

    private final UserSessionRepository repository = mock(UserSessionRepository.class);
    private final UserSessionCleanupService service = new UserSessionCleanupService(repository);

    @Test
    void deletesInBatchesUntilTableIsDrained() {
        when(repository.deleteExpiredBatch(any(), anyInt())).thenReturn(1000, 300);

        int deleted = service.cleanupExpired(Instant.parse("2026-10-26T00:00:00Z"));

        assertThat(deleted).isEqualTo(1300);
        // 第一批满额说明可能还有积压，必须继续；第二批不足额即可停止。
        verify(repository, org.mockito.Mockito.times(2)).deleteExpiredBatch(any(), anyInt());
    }

    @Test
    void stopsAfterSinglePassWhenNothingIsExpired() {
        when(repository.deleteExpiredBatch(any(), anyInt())).thenReturn(0);

        assertThat(service.cleanupExpired(Instant.parse("2026-10-26T00:00:00Z"))).isZero();
        verify(repository).deleteExpiredBatch(any(), anyInt());
    }

    @Test
    void capsOneRunSoASingleSweepCannotRunForever() {
        when(repository.deleteExpiredBatch(any(), anyInt())).thenReturn(1000);

        int deleted = service.cleanupExpired(Instant.parse("2026-10-26T00:00:00Z"));

        // MAX_BATCHES = 10，单轮最多处理 10 批，剩余积压留给下一次调度。
        assertThat(deleted).isEqualTo(10_000);
        verify(repository, org.mockito.Mockito.times(10)).deleteExpiredBatch(any(), anyInt());
        verify(repository, never()).findAll();
    }
}
