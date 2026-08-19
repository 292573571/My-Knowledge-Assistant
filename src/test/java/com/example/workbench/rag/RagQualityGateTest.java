package com.example.workbench.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class RagQualityGateTest {

    @Test
    void keepsOnlySourcesApprovedByTheEvaluator() {
        LocalChatClient chatClient = Mockito.mock(LocalChatClient.class);
        when(chatClient.generate(anyString())).thenReturn("KEEP: 2");
        RagQualityGate gate = new RagQualityGate(chatClient, true);
        SourceDocument unrelated = new SourceDocument("one", "RAG 是什么？", "RAG", "rag.md", "docs/rag.md", 0);
        SourceDocument relevant = new SourceDocument("two", "MCP 是连接 AI 与外部工具的标准协议。", "MCP", "mcp.md", "docs/mcp.md", 1);

        List<SourceDocument> sources = gate.relevantSources("MCP是什么？", List.of(unrelated, relevant));

        assertThat(sources).containsExactly(relevant);
    }

    @Test
    void rejectsAnAnswerWhenTheEvaluatorReturnsFail() {
        LocalChatClient chatClient = Mockito.mock(LocalChatClient.class);
        when(chatClient.generate(anyString())).thenReturn("FAIL");
        RagQualityGate gate = new RagQualityGate(chatClient, true);
        SourceDocument source = new SourceDocument("one", "MCP 是协议。", "MCP", "mcp.md", "docs/mcp.md", 0);

        assertThat(gate.approvesAnswer("MCP是什么？", "RAG 是检索增强生成。", List.of(source))).isFalse();
    }

    @Test
    void passesThroughSourcesWhenTheEvaluatorIsUnavailable() {
        LocalChatClient chatClient = Mockito.mock(LocalChatClient.class);
        when(chatClient.generate(anyString())).thenReturn(null);
        RagQualityGate gate = new RagQualityGate(chatClient, true);
        SourceDocument source = new SourceDocument("one", "MCP 是协议。", "MCP", "mcp.md", "docs/mcp.md", 0);

        assertThat(gate.relevantSources("MCP是什么？", List.of(source))).containsExactly(source);
        assertThat(gate.approvesAnswer("MCP是什么？", "MCP 是协议。", List.of(source))).isTrue();
    }

    @Test
    void passesThroughSourcesWhenTheEvaluatorReturnsMalformedVerdict() {
        LocalChatClient chatClient = Mockito.mock(LocalChatClient.class);
        when(chatClient.generate(anyString())).thenReturn("这些资料看起来可能相关");
        RagQualityGate gate = new RagQualityGate(chatClient, true);
        SourceDocument source = new SourceDocument("one", "MCP 是协议。", "MCP", "mcp.md", "docs/mcp.md", 0);

        assertThat(gate.relevantSources("MCP是什么？", List.of(source))).containsExactly(source);
    }
}
