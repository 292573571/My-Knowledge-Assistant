package com.example.workbench.streaming;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class StreamSessionTest {

    @Test
    void appendsAssignIncreasingSequence() {
        StreamSession session = new StreamSession("s1");
        session.append("token", "a");
        session.append("token", "b");
        StreamChunk done = session.append("done", Map.of("response", "ok"));
        assertEquals(1L, session.snapshot(0).get(0).seq());
        assertEquals(3L, done.seq());
        assertEquals(StreamSession.Status.RUNNING, session.status());
    }

    @Test
    void snapshotReturnsOnlyChunksAfterFromSeq() {
        StreamSession session = new StreamSession("s2");
        session.append("token", "a");
        session.append("token", "b");
        session.append("token", "c");
        List<StreamChunk> afterOne = session.snapshot(1);
        assertEquals(List.of(2L, 3L), afterOne.stream().map(StreamChunk::seq).toList());
    }

    @Test
    void liveSubscriberReceivesChunksAfterFromSeqAndUnsubscribeStopsDelivery() {
        StreamSession session = new StreamSession("s3");
        session.append("token", "a"); // seq1
        List<String> received = new ArrayList<>();
        Runnable unsubscribe = session.subscribe(0,
                chunk -> received.add(chunk.event() + ":" + chunk.seq()),
                terminal -> received.add("TERM:" + terminal.event()));
        session.append("token", "b"); // seq2 live
        session.append("done", Map.of()); // seq3 live (delivered via onChunk)
        unsubscribe.run();
        session.append("token", "d"); // seq4 must NOT be delivered
        assertEquals(List.of("token:2", "done:3"), received);
    }

    @Test
    void replayThenSubscribeDeliversEachChunkExactlyOnce() {
        StreamSession session = new StreamSession("s4");
        session.append("token", "a"); // seq1
        session.append("token", "b"); // seq2
        List<String> received = new ArrayList<>();
        List<StreamChunk> snapshot = session.snapshot(1); // only seq2
        long lastSnapshotSeq = snapshot.isEmpty() ? 1 : snapshot.get(snapshot.size() - 1).seq();
        Runnable unsubscribe = session.subscribe(lastSnapshotSeq,
                chunk -> received.add(chunk.event() + ":" + chunk.seq()),
                terminal -> received.add("TERM:" + terminal.event()));
        for (StreamChunk chunk : snapshot) {
            if (chunk.seq() > 1) received.add(chunk.event() + ":" + chunk.seq());
        }
        session.append("token", "c"); // seq3 live
        unsubscribe.run();
        assertEquals(List.of("token:2", "token:3"), received);
    }

    @Test
    void subscribeAfterTerminalDeliversTerminalImmediately() {
        StreamSession session = new StreamSession("s5");
        StreamChunk done = session.append("done", Map.of("response", "ok"));
        session.markDone(done);
        assertEquals(StreamSession.Status.DONE, session.status());
        List<String> received = new ArrayList<>();
        session.subscribe(0,
                chunk -> received.add(chunk.event()),
                terminal -> received.add("TERM:" + terminal.event()));
        assertEquals(List.of("TERM:done"), received);
    }

    @Test
    void markFailedTransitionsStateAndNotifiesSubscribers() {
        StreamSession session = new StreamSession("s6");
        List<String> received = new ArrayList<>();
        session.subscribe(0,
                chunk -> received.add(chunk.event()),
                terminal -> received.add("TERM:" + terminal.event()));
        session.append("token", "a");
        StreamChunk error = session.append("error", Map.of("message", "boom"));
        session.markFailed(error);
        assertEquals(StreamSession.Status.FAILED, session.status());
        assertTrue(received.contains("token"));
        assertTrue(received.contains("TERM:error"));
    }
}
