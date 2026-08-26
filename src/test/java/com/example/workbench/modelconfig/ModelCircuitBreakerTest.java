package com.example.workbench.modelconfig;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

class ModelCircuitBreakerTest {

    @Test
    void closesInitiallyAndOpensAfterConsecutiveFailures() {
        ModelCircuitBreaker breaker = new ModelCircuitBreaker(2, 50);
        String model = "primary";
        assertTrue(breaker.allowRequest(model));
        breaker.recordFailure(model);
        // 1 次失败未达阈值,仍允许
        assertTrue(breaker.allowRequest(model));
        breaker.recordFailure(model);
        // 连续 2 次失败达到阈值,熔断
        assertFalse(breaker.allowRequest(model));
    }

    @Test
    void successResetsBreaker() throws InterruptedException {
        ModelCircuitBreaker breaker = new ModelCircuitBreaker(1, 50);
        String model = "primary";
        breaker.recordFailure(model);
        assertFalse(breaker.allowRequest(model));
        breaker.recordSuccess(model);
        assertTrue(breaker.allowRequest(model));
    }

    @Test
    void opensAgainAfterHalfOpenFailureAndClosesAfterSuccess() throws InterruptedException {
        ModelCircuitBreaker breaker = new ModelCircuitBreaker(1, 30);
        String model = "primary";
        breaker.recordFailure(model); // open
        assertFalse(breaker.allowRequest(model));
        // 冷却结束后进入 half-open 并放行;轮询等待以兼容粗粒度时钟(jvm/沙箱)
        awaitTrue(() -> breaker.allowRequest(model), 2000);
        breaker.recordFailure(model); // half-open 失败 -> 重新 open
        assertFalse(breaker.allowRequest(model));
        awaitTrue(() -> breaker.allowRequest(model), 2000);
        breaker.recordSuccess(model); // 恢复
        assertTrue(breaker.allowRequest(model));
    }

    private void awaitTrue(BooleanSupplier condition, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            TimeUnit.MILLISECONDS.sleep(5);
        }
        assertTrue(condition.getAsBoolean(), "condition not satisfied within " + timeoutMs + "ms");
    }
}
