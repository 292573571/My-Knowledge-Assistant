package com.example.workbench.modelconfig;

/**
 * 模型熔断状态的存储后端。
 *
 * <p>进程内实现适用于单实例;Redis 实现让多个实例共享同一模型的健康判断 ——
 * 一个实例发现模型已经不可用后,其他实例无需重复试错,直接走备用模型。</p>
 *
 * <p>状态是启发式的,允许短暂不一致:读-改-写之间的竞态最多导致多放行一次探测请求,
 * 不会造成正确性问题,因此不引入分布式锁。</p>
 */
public interface CircuitBreakerStateStore {

    /** 后端名称,用于启动日志与诊断。 */
    String name();

    /** 读取模型状态;从未记录过时返回 {@link Snapshot#closed()}。 */
    Snapshot read(String model);

    /** 写入模型状态。 */
    void write(String model, Snapshot snapshot);

    enum State {
        CLOSED,
        OPEN,
        HALF_OPEN
    }

    /**
     * 熔断状态快照。
     *
     * @param state               当前状态
     * @param consecutiveFailures 连续失败次数
     * @param openedAt            进入 OPEN 的时间戳(毫秒),未熔断时为 0
     */
    record Snapshot(State state, int consecutiveFailures, long openedAt) {

        public static Snapshot closed() {
            return new Snapshot(State.CLOSED, 0, 0L);
        }
    }
}
