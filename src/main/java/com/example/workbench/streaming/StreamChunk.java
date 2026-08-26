package com.example.workbench.streaming;

/**
 * 流式回答中的一个有序事件片段。
 *
 * <p>每个片段携带全局自增序号 {@link #seq()},用于在客户端断线重连时精确地从断点续传。
 * {@link #event()} 对应 SSE 事件名(token / source / tool_call_* / done / error / session / stream_init),
 * {@link #data()} 为事件载荷。</p>
 */
public record StreamChunk(long seq, String event, Object data) {

    public boolean isTerminal() {
        return "done".equals(event) || "error".equals(event);
    }
}
