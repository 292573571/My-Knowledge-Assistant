package com.example.workbench.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class EvalGateControllerTest {

    @Mock
    private EvalGateService evalGateService;

    private EvalController controller;

    @BeforeEach
    void setUp() {
        controller = new EvalController(null, null, null, null, null, null, null, evalGateService, null);
    }

    @Test
    void rejectsInvalidTokenWithoutRunning() {
        when(evalGateService.authorized("wrong")).thenReturn(false);

        assertThatThrownBy(() -> controller.gate("wrong", null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("运维令牌无效");
        verify(evalGateService, never()).run(null);
    }

    @Test
    void delegatesWithValidToken() {
        when(evalGateService.authorized("secret")).thenReturn(true);
        when(evalGateService.run("REGRESSION")).thenReturn(
                new EvalGateService.GateResult(true, "REGRESSION", "admin", 10, 9, 0.9, 0.9, 0.8, 0.0,
                        "run-1", List.of(), List.of()));

        EvalGateService.GateResult result = controller.gate("secret", "REGRESSION");

        assertThat(result.passed()).isTrue();
        assertThat(result.total()).isEqualTo(10);
        verify(evalGateService).run("REGRESSION");
    }
}
