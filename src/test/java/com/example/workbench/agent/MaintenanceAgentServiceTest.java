package com.example.workbench.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.workbench.auth.AdminAuthorizationService;
import com.example.workbench.auth.AppUser;
import com.example.workbench.auth.SystemRole;
import com.example.workbench.auth.UserConversationScope;
import com.example.workbench.rag.DocumentIngestionService;
import com.example.workbench.rag.DocumentTaskService;
import com.example.workbench.rag.DocumentTaskType;
import com.example.workbench.workspace.WorkspaceAccessContext;
import com.example.workbench.workspace.WorkspaceRole;
import com.example.workbench.workspace.WorkspaceService;
import com.example.workbench.workspace.WorkspaceType;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.web.server.ResponseStatusException;

class MaintenanceAgentServiceTest {

    private DocumentTaskService taskService;
    private WorkspaceService workspaceService;
    private MaintenanceAgentService service;
    private final AdminAuthorizationService adminAuthorization = new AdminAuthorizationService("");

    private final AppUser superAdmin = new AppUser("admin", "Admin", "hash");
    private final AppUser normalUser = new AppUser("alice", "Alice", "hash");

    @BeforeEach
    void setUp() {
        taskService = mock(DocumentTaskService.class);
        workspaceService = mock(WorkspaceService.class);
        service = new MaintenanceAgentService(mock(ChatClient.class), mock(MaintenanceReadOnlyService.class),
                taskService, mock(DocumentIngestionService.class), adminAuthorization, workspaceService,
                new InMemoryMaintenancePendingActionStore());
    }

    @Test
    void rejectsRebuildAllForNonSuperAdmin() {
        assertThatThrownBy(() -> service.chat(normalUser,
                context(normalUser, "personal-2", WorkspaceType.PERSONAL), "重建所有向量索引"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("超级管理员");
    }

    @Test
    void proposesRebuildAllForSuperAdmin() {
        when(workspaceService.allWorkspaceAccesses(superAdmin)).thenReturn(List.of(
                context(superAdmin, "org-1", WorkspaceType.ORG),
                context(superAdmin, "team-1", WorkspaceType.TEAM),
                context(superAdmin, "personal-1", WorkspaceType.PERSONAL)));

        MaintenanceAgentResult result = service.chat(superAdmin,
                context(superAdmin, "org-1", WorkspaceType.ORG), "重建所有向量索引");

        assertThat(result.pendingAction()).isNotNull();
        assertThat(result.pendingAction().action()).isEqualTo(MaintenanceAction.REBUILD_ALL_INDEX);
        assertThat(result.pendingAction().confirmationToken()).isNotBlank();
        assertThat(result.pendingAction().description()).contains("3 个知识空间");
        assertThat(result.readOnly()).isFalse();
    }

    @Test
    void confirmSubmitsRebuildTaskForEveryWorkspace() {
        WorkspaceAccessContext org = context(superAdmin, "org-1", WorkspaceType.ORG);
        WorkspaceAccessContext team = context(superAdmin, "team-1", WorkspaceType.TEAM);
        when(workspaceService.allWorkspaceAccesses(superAdmin)).thenReturn(List.of(org, team));
        when(taskService.createMaintenance(org, DocumentTaskType.REBUILD, null))
                .thenReturn(task("task-1", org.workspaceId()));
        when(taskService.createMaintenance(team, DocumentTaskType.REBUILD, null))
                .thenReturn(task("task-2", team.workspaceId()));

        MaintenanceAgentResult proposal = service.chat(superAdmin, org, "重建所有向量索引");
        MaintenanceWriteResult write = service.confirm(superAdmin, org,
                proposal.pendingAction().confirmationToken());

        assertThat(write.answer()).contains("2 个知识空间");
        assertThat(write.tasks()).containsExactly(
                new MaintenanceTaskReference("task-1", "org-1"),
                new MaintenanceTaskReference("task-2", "team-1"));
        verify(taskService).createMaintenance(org, DocumentTaskType.REBUILD, null);
        verify(taskService).createMaintenance(team, DocumentTaskType.REBUILD, null);
    }

    private static com.example.workbench.rag.DocumentTaskResponse task(String taskId, String workspaceId) {
        return new com.example.workbench.rag.DocumentTaskResponse(taskId, DocumentTaskType.REBUILD,
                com.example.workbench.rag.DocumentTaskStatus.QUEUED, "QUEUED", 5, workspaceId,
                "重建空间索引", null, 0, 3, null, true, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                java.time.Instant.now(), null, null, false);
    }

    @Test
    void keepsSingleWorkspaceRebuildIntent() {
        MaintenanceAgentResult result = service.chat(superAdmin,
                context(superAdmin, "team-1", WorkspaceType.TEAM), "重建索引");

        assertThat(result.pendingAction()).isNotNull();
        assertThat(result.pendingAction().action()).isEqualTo(MaintenanceAction.REBUILD_INDEX);
    }

    @Test
    void confirmRejectsWhenSuperAdminPrivilegeWasRevoked() {
        AppUser promoted = new AppUser("root", "Root", "hash");
        promoted.changeSystemRole(SystemRole.SUPER_ADMIN);
        WorkspaceAccessContext workspaceContext = context(promoted, "org-1", WorkspaceType.ORG);
        when(workspaceService.allWorkspaceAccesses(promoted)).thenReturn(List.of(workspaceContext));

        MaintenanceAgentResult proposal = service.chat(promoted, workspaceContext, "重建所有向量索引");
        promoted.changeSystemRole(SystemRole.USER);

        // 令牌签发后角色被降级，确认时必须再次拦截，且不能提交任何任务。
        assertThatThrownBy(() -> service.confirm(promoted, workspaceContext,
                proposal.pendingAction().confirmationToken()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("超级管理员");
        verify(taskService, times(0)).createMaintenance(any(), any(), any());
    }

    private static WorkspaceAccessContext context(AppUser user, String workspaceId, WorkspaceType type) {
        return new WorkspaceAccessContext(UserConversationScope.ownerId(user), workspaceId,
                WorkspaceRole.OWNER, type);
    }
}
