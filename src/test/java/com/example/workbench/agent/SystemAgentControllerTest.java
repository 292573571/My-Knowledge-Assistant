package com.example.workbench.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.workbench.auth.AdminAuthorizationService;
import com.example.workbench.auth.AppUser;
import com.example.workbench.auth.AuthFilter;
import com.example.workbench.auth.SystemRole;
import com.example.workbench.workspace.WorkspaceAccessContext;
import com.example.workbench.workspace.WorkspaceRole;
import com.example.workbench.workspace.WorkspaceService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class SystemAgentControllerTest {

    private final SystemAgentService agentService = mock(SystemAgentService.class);
    private final WorkspaceService workspaceService = mock(WorkspaceService.class);
    private final AdminAuthorizationService adminAuthorization = new AdminAuthorizationService("");
    private final SystemAgentController controller =
            new SystemAgentController(agentService, workspaceService, adminAuthorization);

    @Test
    void rejectsRequestsWithoutAuthenticatedUser() {
        HttpServletRequest httpRequest = mock(HttpServletRequest.class);

        assertThatThrownBy(() -> controller.chat(request(), httpRequest))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED));
        verifyNoInteractions(agentService, workspaceService);
    }

    @Test
    void rejectsNonAdminUsersBeforeResolvingWorkspace() {
        AppUser user = new AppUser("alice", "Alice", "hash");
        HttpServletRequest httpRequest = authenticatedRequest(user);

        assertThatThrownBy(() -> controller.chat(request(), httpRequest))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
        verifyNoInteractions(agentService, workspaceService);
    }

    @Test
    void resolvesRegularAdminWorkspaceAccess() {
        AppUser user = new AppUser("ops", "Ops", "hash");
        user.changeSystemRole(SystemRole.ADMIN);
        WorkspaceAccessContext access = new WorkspaceAccessContext("user-1", "workspace-a", WorkspaceRole.OWNER);
        MaintenanceAgentRequest request = request();
        MaintenanceAgentResult expected = result();
        HttpServletRequest httpRequest = authenticatedRequest(user);
        when(workspaceService.access(user, "workspace-a")).thenReturn(access);
        when(agentService.chat(user, access, request.message())).thenReturn(expected);

        assertThat(controller.chat(request, httpRequest)).isSameAs(expected);
        verify(workspaceService).access(user, "workspace-a");
        verify(agentService).chat(user, access, request.message());
    }

    @Test
    void resolvesSystemAccessForSuperAdmin() {
        AppUser user = new AppUser("admin", "Admin", "hash");
        WorkspaceAccessContext access = new WorkspaceAccessContext("admin", "workspace-a", WorkspaceRole.OWNER);
        MaintenanceAgentRequest request = request();
        MaintenanceAgentResult expected = result();
        HttpServletRequest httpRequest = authenticatedRequest(user);
        when(workspaceService.systemAccess(user, "workspace-a")).thenReturn(access);
        when(agentService.chat(user, access, request.message())).thenReturn(expected);

        assertThat(controller.chat(request, httpRequest)).isSameAs(expected);
        verify(workspaceService).systemAccess(user, "workspace-a");
        verify(agentService).chat(user, access, request.message());
    }

    private static MaintenanceAgentRequest request() {
        return new MaintenanceAgentRequest("workspace-a", "查询系统状态");
    }

    private static MaintenanceAgentResult result() {
        return new MaintenanceAgentResult("当前正常", List.of(), 1, true, null);
    }

    private static HttpServletRequest authenticatedRequest(AppUser user) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getAttribute(AuthFilter.AUTHENTICATED_USER_ATTRIBUTE)).thenReturn(user);
        return request;
    }
}
