package com.example.workbench.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.workbench.auth.AppUser;
import com.example.workbench.rag.RagSource;
import com.example.workbench.workspace.WorkspaceAccessContext;
import com.example.workbench.workspace.WorkspaceRole;
import java.util.List;
import org.junit.jupiter.api.Test;

class TeachingAgentToolsTest {

    private final TeachingReadOnlyService readOnlyService = mock(TeachingReadOnlyService.class);
    private final TeachingAgentContext context = new TeachingAgentContext(
            new AppUser("alice", "Alice", "hash"),
            new WorkspaceAccessContext("user-1", "workspace-a", WorkspaceRole.VIEWER),
            "session-1", "Agent", TeachingStage.EXPLAIN, TeachingUserLevel.BEGINNER);

    @Test
    void searchUsesFixedRequestContextAndCollectsDisplayedSources() {
        RagSource firstChunk = new RagSource("agent.pdf", 10, "第一段", 0.2, "Agent", "docs/agent.pdf", 3);
        RagSource samePage = new RagSource("agent.pdf", 11, "第二段", 0.3, "Agent", "docs/agent.pdf", 3);
        TeachingReadOnlyService.KnowledgeSearchResult expected =
                new TeachingReadOnlyService.KnowledgeSearchResult("Agent 是什么", List.of(firstChunk, samePage));
        when(readOnlyService.search(context, "Agent 是什么", 5)).thenReturn(expected);
        TeachingAgentTools tools = new TeachingAgentTools(readOnlyService, context);

        assertThat(tools.searchKnowledge("Agent 是什么", 5)).isEqualTo(expected);
        assertThat(tools.sources()).containsExactly(firstChunk);
        assertThat(tools.invocations()).containsExactly(
                new TeachingAgentTools.Invocation("searchKnowledge", "SUCCEEDED"));
        verify(readOnlyService).search(context, "Agent 是什么", 5);
    }

    @Test
    void rejectsCallsAfterReadOnlyBudget() {
        when(readOnlyService.recentLearningRecords(context, 3))
                .thenReturn(new TeachingReadOnlyService.LearningHistorySummary(List.of()));
        TeachingAgentTools tools = new TeachingAgentTools(readOnlyService, context);

        for (int index = 0; index < TeachingAgentTools.MAX_TOOL_CALLS; index++) {
            tools.getRecentLearningRecords(3);
        }

        assertThatThrownBy(() -> tools.getRecentLearningRecords(3))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("工具调用上限");
        assertThat(tools.invocations()).last().isEqualTo(
                new TeachingAgentTools.Invocation("getRecentLearningRecords", "REJECTED"));
    }
}
