package com.example.workbench.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.workbench.auth.AppUser;
import com.example.workbench.auth.AuthFilter;
import com.example.workbench.workspace.WorkspaceAccessContext;
import com.example.workbench.workspace.WorkspaceRole;
import com.example.workbench.workspace.WorkspaceService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class SupportAgentControllerTest {

    private final SupportAgentService agentService = mock(SupportAgentService.class);
    private final WorkspaceService workspaceService = mock(WorkspaceService.class);
    private final SupportAgentController controller = new SupportAgentController(agentService, workspaceService);

    @Test
    void rejectsRequestsWithoutAuthenticatedUser() {
        HttpServletRequest httpRequest = mock(HttpServletRequest.class);

        assertThatThrownBy(() -> controller.chat(request(), httpRequest))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED));
        verifyNoInteractions(agentService, workspaceService);
    }

    @Test
    void resolvesAuthenticatedUsersWorkspaceBeforeCallingAgent() {
        AppUser user = new AppUser("alice", "Alice", "hash");
        WorkspaceAccessContext access = new WorkspaceAccessContext("user-1", "workspace-a", WorkspaceRole.VIEWER);
        MaintenanceAgentRequest request = request();
        MaintenanceAgentResult expected = new MaintenanceAgentResult("帮助内容", List.of(), 1, true, null);
        HttpServletRequest httpRequest = mock(HttpServletRequest.class);
        when(httpRequest.getAttribute(AuthFilter.AUTHENTICATED_USER_ATTRIBUTE)).thenReturn(user);
        when(workspaceService.access(user, "workspace-a")).thenReturn(access);
        when(agentService.chat(user, access, request.message())).thenReturn(expected);

        assertThat(controller.chat(request, httpRequest)).isSameAs(expected);
        verify(workspaceService).access(user, "workspace-a");
        verify(agentService).chat(user, access, request.message());
    }

    private static MaintenanceAgentRequest request() {
        return new MaintenanceAgentRequest("workspace-a", "怎么上传文档");
    }
}
