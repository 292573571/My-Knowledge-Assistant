package com.example.workbench.observability;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

class RagMetricsTest {

    @Test
    void recordsStageRetrievalModelAndTaskMetricsWithoutTenantLabels() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RagMetrics metrics = new RagMetrics(registry);

        assertThat(metrics.time("fusion", () -> "result")).isEqualTo("result");
        metrics.recordRetrievalCount("dense", 3);
        metrics.recordContextCount(2);
        metrics.recordModelCall("model-a", "primary", "success", 1_000_000, 12, 4);
        metrics.recordFallback("local-answer");
        metrics.recordDocumentTask("UPLOAD", "SUCCEEDED");

        assertThat(registry.get("rag.stage.duration").tag("stage", "fusion").timer().count()).isEqualTo(1);
        assertThat(registry.get("rag.retrieval.result.count").tag("channel", "dense").summary().totalAmount())
                .isEqualTo(3);
        assertThat(registry.get("rag.context.source.count").summary().totalAmount()).isEqualTo(2);
        assertThat(registry.get("rag.model.call.duration").tag("model", "model-a").timer().count()).isEqualTo(1);
        assertThat(registry.get("rag.model.tokens").tag("type", "prompt").counter().count()).isEqualTo(12);
        assertThat(registry.get("rag.model.fallback.total").counter().count()).isEqualTo(1);
        assertThat(registry.get("document.task.transitions.total").tag("status", "SUCCEEDED").counter().count())
                .isEqualTo(1);
        assertThat(registry.getMeters()).allSatisfy(meter -> assertThat(meter.getId().getTags())
                .noneMatch(tag -> tag.getKey().equals("userId") || tag.getKey().equals("workspaceId")
                        || tag.getKey().equals("requestId") || tag.getKey().equals("query")));
    }

    @Test
    void marksFailedStageAsError() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RagMetrics metrics = new RagMetrics(registry);

        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> metrics.time("vector_retrieval", () -> { throw new IllegalStateException("failed"); }))
                .isInstanceOf(IllegalStateException.class);

        assertThat(registry.get("rag.stage.duration").tag("stage", "vector_retrieval")
                .tag("outcome", "error").timer().count()).isEqualTo(1);
    }
}
