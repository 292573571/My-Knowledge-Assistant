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
        assertThat(response.review()).isNull();
        assertThat(response.practice()).isNotNull();
        assertThat(response.practice().status()).isEqualTo(TeachingPracticeStatus.PENDING);
        assertThat(response.practice().question()).contains("Agent");
        assertThat(response.sessionSummary().status()).isEqualTo(TeachingSessionStatus.IN_PROGRESS);
        assertThat(response.sessionSummary().score()).isEqualTo(5);
        assertThat(response.sessionSummary().masteryPercent()).isEqualTo(50);
        assertThat(response.sessionSummary().completedItems()).isEqualTo(1);
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
    void includesStructuredReviewForAnswerThatMissesToolConcept() {
        TeachingCheckPrompt prompt = service.createPending(user, access, "lesson-2", "Agent", "检查问题？");

        TeachingCheckResponse response = service.submit(user, access,
                new SubmitTeachingCheckRequest("workspace-a", "lesson-2", prompt.checkId(), "Agent 是一个会回答问题的模型。"));

        assertThat(response.passed()).isFalse();
        assertThat(response.nextAction()).isEqualTo(TeachingNextAction.RECHECK);
        assertThat(response.stage()).isEqualTo(TeachingStage.REVIEW);
        assertThat(response.review()).isNotNull();
        assertThat(response.review().weakPoint()).contains("工具调用");
        assertThat(response.review().explanation()).contains("工具");
        assertThat(response.review().suggestion()).contains("Agent");
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

    @Test
    void scoresPracticeAndMakesRepeatedSubmissionIdempotent() {
        TeachingCheckPrompt prompt = service.createPending(user, access, "lesson-practice", "Agent",
                "检查问题：Agent 为什么需要调用工具？");
        TeachingCheckResponse checked = service.submit(user, access, new SubmitTeachingCheckRequest(
                "workspace-a", "lesson-practice", prompt.checkId(),
                "Agent 根据目标调用工具，结果回到上下文。例如搜索知识库。"));
        TeachingPracticePrompt practice = checked.practice();
        SubmitTeachingPracticeRequest request = new SubmitTeachingPracticeRequest(
                "workspace-a", "lesson-practice", practice.practiceId(),
                "Agent 的目标是解决问题，会调用工具检索知识库，工具结果回到上下文并影响下一步决策。实际任务是回答资料问题。 ");

        TeachingPracticeResponse first = service.submitPractice(user, access, request);
        TeachingPracticeResponse second = service.submitPractice(user, access, request);

        assertThat(first).isSameAs(second);
        assertThat(first.status()).isEqualTo(TeachingPracticeStatus.COMPLETED);
        assertThat(first.stage()).isEqualTo(TeachingStage.PRACTICE);
        assertThat(first.nextAction()).isEqualTo(TeachingNextAction.COMPLETE);
        assertThat(first.passed()).isTrue();
        assertThat(first.sessionSummary().status()).isEqualTo(TeachingSessionStatus.MASTERED);
        assertThat(first.sessionSummary().completedItems()).isEqualTo(2);
        assertThat(first.sessionSummary().masteryPercent()).isEqualTo(100);
    }

    @Test
    void rejectsDifferentPracticeAnswerAndWrongSession() {
        TeachingCheckPrompt prompt = service.createPending(user, access, "lesson-practice", "Agent", "检查问题？");
        TeachingCheckResponse checked = service.submit(user, access, new SubmitTeachingCheckRequest(
                "workspace-a", "lesson-practice", prompt.checkId(),
                "Agent 根据目标调用工具，结果回到上下文。例如搜索资料。"));
        String practiceId = checked.practice().practiceId();
        service.submitPractice(user, access, new SubmitTeachingPracticeRequest(
                "workspace-a", "lesson-practice", practiceId, "第一次实践答案，包含目标和工具。"));

        assertThatThrownBy(() -> service.submitPractice(user, access, new SubmitTeachingPracticeRequest(
                "workspace-a", "lesson-practice", practiceId, "第二个不同答案。")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("不同答案");
        assertThatThrownBy(() -> service.submitPractice(user, access, new SubmitTeachingPracticeRequest(
                "workspace-a", "other-session", practiceId, "第一次实践答案，包含目标和工具。")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("不存在或不可访问");
    }

    @Test
    void sendsWeakPracticeAnswerToReviewWithoutChangingTheSubmittedPractice() {
        TeachingCheckPrompt prompt = service.createPending(user, access, "lesson-practice-review", "Agent", "检查问题？");
        TeachingCheckResponse checked = service.submit(user, access, new SubmitTeachingCheckRequest(
                "workspace-a", "lesson-practice-review", prompt.checkId(),
                "Agent 根据目标调用工具，结果回到上下文。例如搜索资料。"));

        TeachingPracticeResponse response = service.submitPractice(user, access,
                new SubmitTeachingPracticeRequest("workspace-a", "lesson-practice-review",
                        checked.practice().practiceId(), "Agent 是模型。"));

        assertThat(response.passed()).isFalse();
        assertThat(response.stage()).isEqualTo(TeachingStage.REVIEW);
        assertThat(response.nextAction()).isEqualTo(TeachingNextAction.RECHECK);
        assertThat(response.review()).isNotNull();
        assertThat(response.status()).isEqualTo(TeachingPracticeStatus.COMPLETED);
        assertThat(response.readOnly()).isTrue();
        assertThat(response.sessionSummary().status()).isEqualTo(TeachingSessionStatus.NEEDS_REVIEW);
        assertThat(response.sessionSummary().nextAction()).isEqualTo(TeachingNextAction.RECHECK);
    }

    @Test
    void doesNotAllowUnknownPracticeIdToBypassUnderstandingCheck() {
        TeachingCheckPrompt prompt = service.createPending(user, access, "lesson-no-skip", "Agent", "检查问题？");
        service.submit(user, access, new SubmitTeachingCheckRequest(
                "workspace-a", "lesson-no-skip", prompt.checkId(), "Agent 是模型。"));

        assertThatThrownBy(() -> service.submitPractice(user, access, new SubmitTeachingPracticeRequest(
                "workspace-a", "lesson-no-skip", "forged-practice-id", "目标、工具、结果。")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("实践不存在或已过期");
    }
}
