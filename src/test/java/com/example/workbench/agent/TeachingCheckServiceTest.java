package com.example.workbench.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.example.workbench.auth.AppUser;
import com.example.workbench.learning.LearningRecordService;
import com.example.workbench.workspace.WorkspaceAccessContext;
import com.example.workbench.workspace.WorkspaceRole;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class TeachingCheckServiceTest {

    private final LearningRecordService learningRecordService = mock(LearningRecordService.class);
    private final TeachingCheckService service = new TeachingCheckService(learningRecordService);
    private final AppUser user = new AppUser("alice", "Alice", "hash");
    private final WorkspaceAccessContext access =
            new WorkspaceAccessContext("alice", "workspace-a", WorkspaceRole.VIEWER);

    @Test
    void scoresCoreConceptsAndReturnsPracticeForPassingAnswer() {
        TeachingCheckPrompt prompt = service.createPending(user, access, "lesson-1", "Agent",
                "理解检查问题：Agent 为什么需要调用工具？");

        TeachingCheckResponse response = service.submit(user, access,
                new SubmitTeachingCheckRequest("workspace-a", "lesson-1", prompt.checkId(),
                        "Agent 根据目标决定调用工具，检索知识库，工具结果回到上下文。例如搜索资料。"));

        assertThat(response.score()).isEqualTo(5);
        assertThat(response.passed()).isTrue();
        assertThat(response.nextAction()).isEqualTo(TeachingNextAction.PRACTICE);
        assertThat(response.stage()).isEqualTo(TeachingStage.CHECK);
    }

    @Test
    void rejectsAnotherWorkspaceAndMakesCompletedSubmissionIdempotent() {
        TeachingCheckPrompt prompt = service.createPending(user, access, "lesson-1", "Agent", "为什么？");
        SubmitTeachingCheckRequest request = new SubmitTeachingCheckRequest(
                "workspace-a", "lesson-1", prompt.checkId(), "我还需要复习这个概念。");

        TeachingCheckResponse first = service.submit(user, access, request);
        assertThat(service.submit(user, access, request)).isSameAs(first);

        WorkspaceAccessContext otherAccess =
                new WorkspaceAccessContext("alice", "workspace-b", WorkspaceRole.VIEWER);
        assertThatThrownBy(() -> service.submit(user, otherAccess,
                new SubmitTeachingCheckRequest("workspace-b", "lesson-1", prompt.checkId(), request.answer())))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("教学检查不存在或不可访问");
    }

    @Test
    void preventsDifferentAnswerReplayAfterScoring() {
        TeachingCheckPrompt prompt = service.createPending(user, access, "lesson-1", "Agent", "为什么？");
        service.submit(user, access, new SubmitTeachingCheckRequest(
                "workspace-a", "lesson-1", prompt.checkId(), "第一次提交答案。"));

        assertThatThrownBy(() -> service.submit(user, access, new SubmitTeachingCheckRequest(
                "workspace-a", "lesson-1", prompt.checkId(), "换一个答案再次提交。")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("已经提交过不同答案");
    }
}
