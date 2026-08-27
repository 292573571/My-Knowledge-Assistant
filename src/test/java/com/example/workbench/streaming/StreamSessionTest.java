package com.example.workbench.streaming;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class StreamSessionTest {

    private static StreamSession newSession(String id) {
        MemoryStreamBufferBackend backend = new MemoryStreamBufferBackend();
        backend.createSession(id, 1L);
        return new StreamSession(id, backend);
    }

    @Test
    void appendsAssignIncreasingSequence() {
        StreamSession session = newSession("s1");
        session.append("token", "a");
        session.append("token", "b");
        StreamChunk done = session.append("done", Map.of("response", "ok"));
        assertEquals(1L, session.snapshot(0).get(0).seq());
        assertEquals(3L, done.seq());
        assertEquals(StreamSession.Status.RUNNING, session.status());
    }

    @Test
    void snapshotReturnsOnlyChunksAfterFromSeq() {
        StreamSession session = newSession("s2");
        session.append("token", "a");
        session.append("token", "b");
        session.append("token", "c");
        List<StreamChunk> afterOne = session.snapshot(1);
        assertEquals(List.of(2L, 3L), afterOne.stream().map(StreamChunk::seq).toList());
    }

    @Test
    void liveSubscriberReceivesChunksAfterFromSeqAndUnsubscribeStopsDelivery() {
        StreamSession session = newSession("s3");
        session.append("token", "a"); // seq1
        List<String> received = new ArrayList<>();
        // fromSeq=1: 不重放 seq1,只注册 seq1 之后的实时投递
        Runnable unsubscribe = session.subscribe(1,
                chunk -> received.add(chunk.event() + ":" + chunk.seq()),
                terminal -> received.add("TERM"));
        session.append("token", "b"); // seq2 live
        session.append("done", Map.of()); // seq3 live (delivered via onChunk)
        unsubscribe.run();
        session.append("token", "d"); // seq4 must NOT be delivered
        assertEquals(List.of("token:2", "done:3"), received);
    }

    @Test
    void replayThenSubscribeDeliversEachChunkExactlyOnce() {
        StreamSession session = newSession("s4");
        session.append("token", "a"); // seq1
        session.append("token", "b"); // seq2
        List<String> received = new ArrayList<>();
        // 新 subscribe 在同一把锁内先重放 seq1 之后的片段(seq2),再注册实时订阅;
        // 不需要手动 snapshot,保证每个片段恰好投递一次。
        Runnable unsubscribe = session.subscribe(1,
                chunk -> received.add(chunk.event() + ":" + chunk.seq()),
                terminal -> received.add("TERM:" + terminal.event()));
        session.append("token", "c"); // seq3 live
        unsubscribe.run();
        assertEquals(List.of("token:2", "token:3"), received);
    }

    @Test
    void subscribeAfterTerminalDeliversTerminalImmediately() {
        StreamSession session = newSession("s5");
        StreamChunk done = session.append("done", Map.of("response", "ok"));
        session.markDone(done);
        assertEquals(StreamSession.Status.DONE, session.status());
        List<String> received = new ArrayList<>();
        // subscribe 时会话已终态:先重放 seq1(done) via onChunk,再 onTerminal(null) 信号关连接
        session.subscribe(0,
                chunk -> received.add(chunk.event()),
                terminal -> received.add("TERM"));
        assertEquals(List.of("done", "TERM"), received);
    }

    @Test
    void markFailedTransitionsStateAndNotifiesSubscribers() {
        StreamSession session = newSession("s6");
        List<String> received = new ArrayList<>();
        session.subscribe(0,
                chunk -> received.add(chunk.event()),
                terminal -> received.add("TERM"));
        session.append("token", "a");
        StreamChunk error = session.append("error", Map.of("message", "boom"));
        session.markFailed(error);
        assertEquals(StreamSession.Status.FAILED, session.status());
        assertTrue(received.contains("token"));
        assertTrue(received.contains("error"));
        assertTrue(received.contains("TERM"));
    }
}
