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

class TeachingAgentControllerTest {

    private final TeachingAgentService agentService = mock(TeachingAgentService.class);
    private final WorkspaceService workspaceService = mock(WorkspaceService.class);
    private final TeachingAgentController controller = new TeachingAgentController(agentService, workspaceService);

    @Test
    void bindsAuthenticatedUserAndAuthorizedWorkspaceBeforeCallingAgent() {
        AppUser user = new AppUser("alice", "Alice", "hash");
        WorkspaceAccessContext access =
                new WorkspaceAccessContext("user-1", "workspace-a", WorkspaceRole.VIEWER);
        TeachingAgentRequest request = new TeachingAgentRequest(
                "workspace-a", "lesson-1", "Agent", TeachingUserLevel.BEGINNER, "请解释 Agent");
        TeachingAgentResult expected = new TeachingAgentResult(
                "讲解", "lesson-1", "Agent", TeachingStage.EXPLAIN, TeachingNextAction.CHECK,
                List.of(), List.of(), 1, true);
        HttpServletRequest httpRequest = mock(HttpServletRequest.class);
        when(httpRequest.getAttribute(AuthFilter.AUTHENTICATED_USER_ATTRIBUTE)).thenReturn(user);
        when(workspaceService.access(user, "workspace-a")).thenReturn(access);
        when(agentService.chat(user, access, request)).thenReturn(expected);

        assertThat(controller.chat(request, httpRequest)).isSameAs(expected);
        verify(workspaceService).access(user, "workspace-a");
        verify(agentService).chat(user, access, request);
    }

    @Test
    void rejectsRequestsWithoutAuthenticatedUser() {
        TeachingAgentRequest request = new TeachingAgentRequest(
                "workspace-a", "lesson-1", "Agent", TeachingUserLevel.BEGINNER, "请解释 Agent");
        HttpServletRequest httpRequest = mock(HttpServletRequest.class);

        assertThatThrownBy(() -> controller.chat(request, httpRequest))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED));
        verifyNoInteractions(workspaceService, agentService);
    }
}
