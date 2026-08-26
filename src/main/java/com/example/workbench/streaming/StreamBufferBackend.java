package com.example.workbench.streaming;

import java.util.List;
import java.util.function.Consumer;

/**
 * 流式片段缓冲的存储后端。
 *
 * <p>把「有序片段的读写」与「订阅推送」彻底分离:{@link StreamSession} 负责本进程内的订阅者管理,
 * 本接口负责片段的持久化与序号分配。这样同一套上层逻辑既能跑在进程内缓冲(单实例、零依赖),
 * 也能跑在 Redis 上(重启不丢、跨实例续传)。</p>
 *
 * <p>实现类必须保证 {@link #nextSeq(String)} 单调递增且并发安全 —— 序号是断点续传的唯一依据。</p>
 */
public interface StreamBufferBackend {

    /** 后端名称,用于启动日志与诊断,例如 {@code memory} / {@code redis}。 */
    String name();

    /** 为指定会话分配下一个片段序号(从 1 开始,严格单调递增)。 */
    long nextSeq(String streamId);

    /** 追加一个已带序号的片段。实现需在写入后通知其他实例(如有)。 */
    void appendChunk(String streamId, StreamChunk chunk);

    /** 读取序号大于 {@code fromSeq} 的片段,按序号升序返回。 */
    List<StreamChunk> readChunks(String streamId, long fromSeq);

    /** 创建会话元数据,状态置为 {@link StreamSession.Status#RUNNING}。 */
    void createSession(String streamId);

    /** 写入终态(DONE / FAILED)与终态片段。 */
    void saveTerminal(String streamId, StreamSession.Status status, StreamChunk terminal);

    /** 读取会话状态;会话不存在(或已过期)时返回 {@code null}。 */
    SessionState readState(String streamId);

    /** 删除会话的全部数据。 */
    void remove(String streamId);

    /**
     * 注册「远端有新片段」的回调,入参为 {@code streamId}。
     *
     * <p>仅 Redis 等分布式实现有实际意义:当另一个实例写入片段时,本实例借此拉取并投递给本地订阅者。
     * 进程内实现可空实现。</p>
     */
    default void onRemoteAppend(Consumer<String> listener) {
        // 进程内实现无需跨实例通知
    }

    /**
     * 会话状态快照。
     *
     * @param status       会话状态
     * @param terminalSeq  终态片段的序号,未终结时为 0
     */
    record SessionState(StreamSession.Status status, long terminalSeq) {
    }
}
