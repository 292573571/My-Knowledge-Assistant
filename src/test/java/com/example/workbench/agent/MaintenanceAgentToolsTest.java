package com.example.workbench.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.workbench.auth.AppUser;
import com.example.workbench.workspace.WorkspaceAccessContext;
import com.example.workbench.workspace.WorkspaceRole;
import java.util.List;
import org.junit.jupiter.api.Test;

class MaintenanceAgentToolsTest {

    private final MaintenanceReadOnlyService readOnlyService = mock(MaintenanceReadOnlyService.class);
    private final AppUser user = new AppUser("alice", "Alice", "hash");
    private final MaintenanceAgentContext context = new MaintenanceAgentContext(user,
            new WorkspaceAccessContext("alice", "workspace-a", WorkspaceRole.EDITOR));

    @Test
    void toolUsesFixedRequestContextAndRecordsInvocation() {
        MaintenanceReadOnlyService.IndexStatusSummary expected =
                new MaintenanceReadOnlyService.IndexStatusSummary("workspace-a", 2, 8);
        when(readOnlyService.indexStatus(context)).thenReturn(expected);

        MaintenanceAgentTools tools = new MaintenanceAgentTools(readOnlyService, context);

        assertThat(tools.getIndexStatus()).isEqualTo(expected);
        assertThat(tools.invocations()).containsExactly(new MaintenanceAgentTools.Invocation(
                "getIndexStatus", "SUCCEEDED"));
        verify(readOnlyService).indexStatus(context);
    }

    @Test
    void rejectsCallsAfterReadOnlyBudget() {
        when(readOnlyService.tasks(context, false))
                .thenReturn(new MaintenanceReadOnlyService.TaskListSummary(List.of()));
        MaintenanceAgentTools tools = new MaintenanceAgentTools(readOnlyService, context);

        for (int i = 0; i < MaintenanceAgentTools.MAX_TOOL_CALLS; i++) {
            tools.listDocumentTasks(false);
        }

        assertThatThrownBy(() -> tools.listDocumentTasks(false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("工具调用上限");
        assertThat(tools.invocations()).last().isEqualTo(
                new MaintenanceAgentTools.Invocation("listDocumentTasks", "REJECTED"));
    }
}
