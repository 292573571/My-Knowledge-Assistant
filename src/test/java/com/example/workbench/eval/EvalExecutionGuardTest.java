package com.example.workbench.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.workbench.auth.AppUser;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class EvalExecutionGuardTest {

    @Test
    void rejectsRunsThatExceedCaseLimit() {
        EvalExecutionGuard guard = new EvalExecutionGuard(2);
        AppUser user = org.mockito.Mockito.mock(AppUser.class);
        org.mockito.Mockito.when(user.getId()).thenReturn(7L);

        assertThatThrownBy(() -> guard.execute(user, 3, () -> "never"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void allowsOnlyOneConcurrentRunPerUser() throws Exception {
        EvalExecutionGuard guard = new EvalExecutionGuard(10);
        AppUser user = org.mockito.Mockito.mock(AppUser.class);
        org.mockito.Mockito.when(user.getId()).thenReturn(7L);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Throwable> backgroundError = new AtomicReference<>();
        Thread running = new Thread(() -> {
            try {
                guard.execute(user, 1, () -> {
                    entered.countDown();
                    try {
                        release.await();
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                    }
                    return "done";
                });
            } catch (Throwable error) {
                backgroundError.set(error);
            }
        });
        running.start();
        entered.await();

        assertThatThrownBy(() -> guard.execute(user, 1, () -> "second"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode())
                        .isEqualTo(HttpStatus.CONFLICT));

        release.countDown();
        running.join();
        assertThat(backgroundError.get()).isNull();
        assertThat(guard.execute(user, 1, () -> "next")).isEqualTo("next");
    }
}
