package com.example.workbench.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.workbench.auth.AppUser;
import com.example.workbench.auth.UserConversationScope;
import com.example.workbench.rag.RagService;
import com.example.workbench.rag.RagSource;
import com.example.workbench.workspace.WorkspaceAccessContext;
import com.example.workbench.workspace.WorkspaceRole;
import com.example.workbench.workspace.WorkspaceService;
import com.example.workbench.workspace.WorkspaceType;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SupportAgentToolsTest {

    private RagService ragService;
    private WorkspaceService workspaceService;
    private MaintenanceReadOnlyService knowledgeReadOnly;
    private SupportAgentTools tools;
    private final AppUser user = new AppUser("alice", "Alice", "hash");

    @BeforeEach
    void setUp() {
        ragService = mock(RagService.class);
        workspaceService = mock(WorkspaceService.class);
        knowledgeReadOnly = mock(MaintenanceReadOnlyService.class);
        tools = new SupportAgentTools(ragService, workspaceService, knowledgeReadOnly,
                new MaintenanceAgentContext(user, new WorkspaceAccessContext(
                        UserConversationScope.ownerId(user), "personal-2", WorkspaceRole.OWNER,
                        WorkspaceType.PERSONAL)));
    }

    @Test
    void searchesOnlyPublicHelpWorkspaces() {
        when(workspaceService.publicWorkspaceIds()).thenReturn(Set.of("public-1"));
        when(ragService.retrieveForAgent(anyString(), anyString(), eq(Set.of("public-1")), anyInt()))
                .thenReturn(List.of(new RagSource("upload-guide.md", 1, "在知识库页面点击上传",
                        0.9, "上传 > 步骤", "/docs/workspaces/public-1/internal.md")));

        List<SupportAgentTools.HelpHit> hits = tools.searchHelpDocs("怎么上传文档", null);

        assertThat(hits).singleElement().satisfies(hit -> {
            assertThat(hit.file()).isEqualTo("upload-guide.md");
            assertThat(hit.snippet()).contains("上传");
            assertThat(hit.headingPath()).isEqualTo("上传 > 步骤");
        });
        // 只检索公共帮助空间，且不把内部路径透出（HelpHit 没有 path 字段）。
        verify(ragService).retrieveForAgent(eq("怎么上传文档"), eq(UserConversationScope.ownerId(user)),
                eq(Set.of("public-1")), eq(3));
    }

    @Test
    void returnsEmptyWhenNoHelpWorkspaceConfigured() {
        when(workspaceService.publicWorkspaceIds()).thenReturn(Set.of());

        assertThat(tools.searchHelpDocs("怎么上传文档", 3)).isEmpty();
        org.mockito.Mockito.verifyNoInteractions(ragService);
    }

    @Test
    void exposesOwnJobStatus() {
        when(knowledgeReadOnly.tasks(org.mockito.ArgumentMatchers.any(), eq(false)))
                .thenReturn(new MaintenanceReadOnlyService.TaskListSummary(List.of()));

        assertThat(tools.getMyJobStatus().tasks()).isEmpty();
    }

    @Test
    void enforcesReadOnlyToolBudget() {
        when(workspaceService.publicWorkspaceIds()).thenReturn(Set.of());

        tools.searchHelpDocs("一", 1);
        tools.searchHelpDocs("二", 1);
        tools.searchHelpDocs("三", 1);
        tools.searchHelpDocs("四", 1);

        assertThatThrownBy(() -> tools.searchHelpDocs("五", 1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("调用上限");
    }
}
