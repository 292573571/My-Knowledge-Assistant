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
    private final TeachingCheckService checkService = mock(TeachingCheckService.class);
    private final WorkspaceService workspaceService = mock(WorkspaceService.class);
    private final TeachingAgentController controller = new TeachingAgentController(agentService, checkService, workspaceService);

    @Test
    void bindsAuthenticatedUserAndAuthorizedWorkspaceBeforeCallingAgent() {
        AppUser user = new AppUser("alice", "Alice", "hash");
        WorkspaceAccessContext access =
                new WorkspaceAccessContext("user-1", "workspace-a", WorkspaceRole.VIEWER);
        TeachingAgentRequest request = new TeachingAgentRequest(
                "workspace-a", "lesson-1", "Agent", TeachingUserLevel.BEGINNER, "请解释 Agent");
        TeachingAgentResult expected = new TeachingAgentResult(
                "讲解", "lesson-1", "Agent", TeachingStage.EXPLAIN, TeachingNextAction.CHECK,
                new TeachingCheckPrompt("check-1", "请解释 Agent？"),
                new TeachingSessionSummary("lesson-7", "lesson-1", TeachingSessionStatus.IN_PROGRESS,
                        TeachingNextAction.CHECK, null, 5, false, false, null, 5, false, false,
                        0, 10, 0, 0, 2, List.of()),
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

    @Test
    void rechecksWorkspaceBeforeSubmittingCheckAnswer() {
        AppUser user = new AppUser("alice", "Alice", "hash");
        WorkspaceAccessContext access =
                new WorkspaceAccessContext("user-1", "workspace-a", WorkspaceRole.VIEWER);
        SubmitTeachingCheckRequest request = new SubmitTeachingCheckRequest(
                "workspace-a", "lesson-1", "check-1", "我的答案");
        TeachingCheckResponse expected = new TeachingCheckResponse(
                "check-1", "lesson-1", "Agent", TeachingStage.REVIEW, TeachingNextAction.RECHECK,
                2, 5, false, "需要复习", new TeachingReview("薄弱点", "解释", "建议"),
                null,
                new TeachingSessionSummary("lesson-7", "lesson-1", TeachingSessionStatus.NEEDS_REVIEW,
                        TeachingNextAction.RECHECK, 2, 5, true, false, null, 5, false, false,
                        2, 10, 20, 1, 2, List.of("薄弱点")),
                true, "2026-08-12", false);
        HttpServletRequest httpRequest = mock(HttpServletRequest.class);
        when(httpRequest.getAttribute(AuthFilter.AUTHENTICATED_USER_ATTRIBUTE)).thenReturn(user);
        when(workspaceService.access(user, "workspace-a")).thenReturn(access);
        when(checkService.submit(user, access, request)).thenReturn(expected);

        assertThat(controller.check(request, httpRequest)).isSameAs(expected);
        verify(workspaceService).access(user, "workspace-a");
        verify(checkService).submit(user, access, request);
    }

    @Test
    void rechecksWorkspaceBeforeSubmittingPracticeAnswer() {
        AppUser user = new AppUser("alice", "Alice", "hash");
        WorkspaceAccessContext access =
                new WorkspaceAccessContext("user-1", "workspace-a", WorkspaceRole.VIEWER);
        SubmitTeachingPracticeRequest request = new SubmitTeachingPracticeRequest(
                "workspace-a", "lesson-1", "practice-1", "实践答案");
        TeachingPracticeResponse expected = new TeachingPracticeResponse(
                "practice-1", "lesson-1", "Agent", "实践问题？", TeachingPracticeStatus.COMPLETED,
                TeachingStage.PRACTICE, TeachingNextAction.COMPLETE, 4, 5, true,
                "实践通过", null,
                new TeachingSessionSummary("lesson-7", "lesson-1", TeachingSessionStatus.MASTERED,
                        TeachingNextAction.COMPLETE, 4, 5, true, true, 4, 5, true, true,
                        8, 10, 80, 2, 2, List.of()),
                false, null, false);
        HttpServletRequest httpRequest = mock(HttpServletRequest.class);
        when(httpRequest.getAttribute(AuthFilter.AUTHENTICATED_USER_ATTRIBUTE)).thenReturn(user);
        when(workspaceService.access(user, "workspace-a")).thenReturn(access);
        when(checkService.submitPractice(user, access, request)).thenReturn(expected);

        assertThat(controller.practice(request, httpRequest)).isSameAs(expected);
        verify(workspaceService).access(user, "workspace-a");
        verify(checkService).submitPractice(user, access, request);
    }
}
