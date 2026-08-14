package com.example.workbench.agent;

import com.example.workbench.auth.AppUser;
import com.example.workbench.learning.LearningRecordService;
import com.example.workbench.workspace.WorkspaceAccessContext;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class TeachingCheckService {

    private static final Duration ATTEMPT_TTL = Duration.ofMinutes(30);
    private static final int MAX_SCORE = 5;
    private static final int PASS_SCORE = 3;
    private static final int MAX_ACTIVE_ATTEMPTS = 20;
    private static final String LESSON_ID = "lesson-7";
    private static final Pattern QUESTION_MARK = Pattern.compile(".*[?？].*");
    private static final List<String> CORE_CONCEPTS = List.of(
            "目标", "工具", "检索", "上下文", "知识库", "模型", "步骤", "结果", "决策", "调用");

    private final LearningRecordService learningRecordService;
    private final TeachingAttemptStore attemptStore;

    public TeachingCheckService(LearningRecordService learningRecordService) {
        this(learningRecordService, new InMemoryTeachingAttemptStore());
    }

    @org.springframework.beans.factory.annotation.Autowired
    public TeachingCheckService(LearningRecordService learningRecordService, TeachingAttemptStore attemptStore) {
        this.learningRecordService = learningRecordService;
        this.attemptStore = attemptStore;
    }

    public TeachingCheckPrompt createPending(AppUser user, WorkspaceAccessContext access,
                                             String sessionId, String topic, String answer) {
        return createPending(user, access, sessionId, topic, answer, null);
    }

    public TeachingCheckPrompt createPending(AppUser user, WorkspaceAccessContext access,
                                             String sessionId, String topic, String answer,
                                             String checkQuestion) {
        cleanupExpired();
        String ownerKey = ownerKey(user);
        long activeCount = attemptStore.countActive(ownerKey, Instant.now());
        if (activeCount >= MAX_ACTIVE_ATTEMPTS) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "教学检查次数过多，请先完成已有课程。");
        }

        String question = checkQuestion == null || checkQuestion.isBlank()
                ? extractQuestion(answer, topic) : checkQuestion.strip();
        String checkId = UUID.randomUUID().toString();
        attemptStore.save(new TeachingAttemptState(checkId, ownerKey, access.workspaceId(), sessionId,
                topic, question, Instant.now().plus(ATTEMPT_TTL), Instant.now()));
        return new TeachingCheckPrompt(checkId, question);
    }

    public TeachingCheckResponse submit(AppUser user, WorkspaceAccessContext access,
                                         SubmitTeachingCheckRequest request) {
        cleanupExpired();
        return attemptStore.withCheckLock(request.checkId().strip(), attempt -> {
            if (attempt == null) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "教学检查不存在或已过期");
            }
            if (attempt.expiresAt.isBefore(Instant.now())) {
                attemptStore.delete(attempt.checkId);
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "教学检查不存在或已过期");
            }
            requireOwnerAndContext(user, access, request, attempt);
            String submittedAnswer = request.answer().strip();
            if (attempt.checkCompleted) {
                if (!attempt.answer.equals(submittedAnswer)) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "该检查题已经提交过不同答案");
                }
                return attempt.response;
            }

            int score = score(attempt, submittedAnswer);
            boolean passed = score >= PASS_SCORE;
            String feedback = feedback(score, passed);
            TeachingReview review = passed ? null : buildReview(attempt, submittedAnswer);
            String recordDate;
            boolean saved = true;
            try {
                recordDate = learningRecordService.recordTeachingCheck(user, attempt.workspaceId, attempt.checkId, attempt.topic,
                        attempt.question, submittedAnswer, score, MAX_SCORE, passed, feedback,
                        review == null ? null : review.weakPoint(),
                        review == null ? null : review.explanation(),
                        review == null ? null : review.suggestion());
            } catch (RuntimeException exception) {
                saved = false;
                recordDate = null;
            }
            attempt.answer = submittedAnswer;
            attempt.checkCompleted = true;
            TeachingPracticePrompt practice = passed ? createPractice(attempt) : null;
            attempt.response = new TeachingCheckResponse(attempt.checkId, attempt.sessionId, attempt.topic,
                    passed ? TeachingStage.CHECK : TeachingStage.REVIEW,
                    passed ? TeachingNextAction.PRACTICE : TeachingNextAction.RECHECK,
                    score, MAX_SCORE, passed, feedback, review, practice,
                    null,
                    saved, recordDate, false);
            attemptStore.save(attempt);
            TeachingSessionSummary sessionSummary = buildSummary(attempt);
            attempt.response = new TeachingCheckResponse(attempt.response.attemptId(), attempt.response.sessionId(),
                    attempt.response.topic(), attempt.response.stage(), attempt.response.nextAction(),
                    attempt.response.score(), attempt.response.maxScore(), attempt.response.passed(),
                    attempt.response.feedback(), attempt.response.review(), attempt.response.practice(),
                    sessionSummary, attempt.response.saved(), attempt.response.recordDate(), attempt.response.readOnly());
            attemptStore.save(attempt);
            return attempt.response;
        });
    }

    public TeachingPracticeResponse submitPractice(AppUser user, WorkspaceAccessContext access,
                                                   SubmitTeachingPracticeRequest request) {
        cleanupExpired();
        return attemptStore.withPracticeLock(request.practiceId().strip(), attempt -> {
            if (attempt == null) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "教学实践不存在或已过期");
            }
            if (attempt.expiresAt.isBefore(Instant.now())) {
                attemptStore.delete(attempt.checkId);
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "教学实践不存在或已过期");
            }
            requirePracticeOwnerAndContext(user, access, request, attempt);
            String submittedAnswer = request.answer().strip();
            if (attempt.practiceCompleted) {
                if (!attempt.practiceAnswer.equals(submittedAnswer)) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "该实践题已经提交过不同答案");
                }
                return attempt.practiceResponse;
            }
            if (!attempt.checkCompleted) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "理解检查尚未完成，不能提交实践");
            }
            if (attempt.response == null || !attempt.response.passed()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "理解检查未通过，不能提交实践");
            }

            int score = practiceScore(attempt, submittedAnswer);
            boolean passed = score >= PASS_SCORE;
            String feedback = practiceFeedback(score, passed);
            String recordDate = null;
            boolean saved = true;
            try {
                recordDate = learningRecordService.recordTeachingPractice(user, attempt.workspaceId, attempt.checkId,
                        attempt.practiceId, attempt.topic, attempt.practiceQuestion, submittedAnswer, score, MAX_SCORE,
                        passed, feedback);
            } catch (RuntimeException exception) {
                saved = false;
            }
            attempt.practiceAnswer = submittedAnswer;
            attempt.practiceCompleted = true;
            attempt.practiceResponse = new TeachingPracticeResponse(
                    attempt.practiceId, attempt.sessionId, attempt.topic, attempt.practiceQuestion,
                    TeachingPracticeStatus.COMPLETED,
                    passed ? TeachingStage.PRACTICE : TeachingStage.REVIEW,
                    passed ? TeachingNextAction.COMPLETE : TeachingNextAction.RECHECK,
                    score, MAX_SCORE, passed, feedback, passed ? null : buildPracticeReview(attempt),
                     null,
                     saved, recordDate, true);
            attemptStore.save(attempt);
            TeachingSessionSummary sessionSummary = buildSummary(attempt);
            attempt.practiceResponse = new TeachingPracticeResponse(attempt.practiceResponse.practiceId(),
                    attempt.practiceResponse.sessionId(), attempt.practiceResponse.topic(),
                    attempt.practiceResponse.question(), attempt.practiceResponse.status(),
                    attempt.practiceResponse.stage(), attempt.practiceResponse.nextAction(),
                    attempt.practiceResponse.score(), attempt.practiceResponse.maxScore(),
                    attempt.practiceResponse.passed(), attempt.practiceResponse.feedback(),
                    attempt.practiceResponse.review(), sessionSummary, attempt.practiceResponse.saved(),
                    attempt.practiceResponse.recordDate(), attempt.practiceResponse.readOnly());
            attemptStore.save(attempt);
            return attempt.practiceResponse;
        });
    }

    public TeachingSessionSummary summary(AppUser user, WorkspaceAccessContext access, String sessionId) {
        cleanupExpired();
        TeachingAttemptState latest = attemptStore.findLatest(ownerKey(user), access.workspaceId(), sessionId.strip())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "教学会话不存在或已过期"));
        synchronized (latest) {
            if (latest.expiresAt.isBefore(Instant.now())) {
                attemptStore.delete(latest.checkId);
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "教学会话不存在或已过期");
            }
            return buildSummary(latest);
        }
    }

    public TeachingPendingActions pendingActions(AppUser user, WorkspaceAccessContext access, String sessionId) {
        cleanupExpired();
        TeachingAttemptState latest = attemptStore.findLatest(ownerKey(user), access.workspaceId(), sessionId.strip())
                .orElse(null);
        if (latest == null || latest.expiresAt.isBefore(Instant.now())) return TeachingPendingActions.empty();
        synchronized (latest) {
            TeachingCheckPrompt check = latest.checkCompleted
                    ? null : new TeachingCheckPrompt(latest.checkId, latest.question);
            TeachingPracticePrompt practice = latest.checkCompleted && latest.response != null
                    && latest.response.passed() && !latest.practiceCompleted && latest.practiceId != null
                    ? new TeachingPracticePrompt(latest.practiceId, latest.practiceQuestion,
                    latest.expiresAt, TeachingPracticeStatus.PENDING)
                    : null;
            return new TeachingPendingActions(check, practice);
        }
    }

    private void requireOwnerAndContext(AppUser user, WorkspaceAccessContext access,
                                        SubmitTeachingCheckRequest request, TeachingAttemptState attempt) {
        if (!attempt.ownerKey.equals(ownerKey(user))
                || !attempt.workspaceId.equals(access.workspaceId())
                || !attempt.sessionId.equals(request.sessionId().strip())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "教学检查不存在或不可访问");
        }
    }

    private void requirePracticeOwnerAndContext(AppUser user, WorkspaceAccessContext access,
                                                SubmitTeachingPracticeRequest request, TeachingAttemptState attempt) {
        if (!attempt.ownerKey.equals(ownerKey(user))
                || !attempt.workspaceId.equals(access.workspaceId())
                || !attempt.sessionId.equals(request.sessionId().strip())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "教学实践不存在或不可访问");
        }
    }

    private TeachingPracticePrompt createPractice(TeachingAttemptState attempt) {
        if (attempt.practiceId == null) {
            attempt.practiceId = UUID.randomUUID().toString();
            attempt.practiceQuestion = "请举一个 Agent 可以帮助你完成的真实任务。"
                    + "说清楚它要调用什么工具，以及工具结果有什么用。";
        }
        return new TeachingPracticePrompt(attempt.practiceId, attempt.practiceQuestion,
                attempt.expiresAt, TeachingPracticeStatus.PENDING);
    }

    private TeachingSessionSummary buildSummary(TeachingAttemptState current) {
        TeachingAttemptState latest = attemptStore.findLatest(current.ownerKey, current.workspaceId, current.sessionId)
                .orElse(current);
        TeachingCheckResponse check = latest.checkCompleted ? latest.response : null;
        TeachingPracticeResponse practice = latest.practiceCompleted ? latest.practiceResponse : null;
        Integer checkScore = check == null ? null : check.score();
        Integer practiceScore = practice == null ? null : practice.score();
        boolean checkPassed = check != null && check.passed();
        boolean practicePassed = practice != null && practice.passed();
        int score = (checkScore == null ? 0 : checkScore) + (practiceScore == null ? 0 : practiceScore);
        int completedItems = (check == null ? 0 : 1) + (practice == null ? 0 : 1);
        TeachingSessionStatus status;
        TeachingNextAction nextAction;
        if (practice != null) {
            status = checkPassed && practicePassed ? TeachingSessionStatus.MASTERED : TeachingSessionStatus.NEEDS_REVIEW;
            nextAction = status == TeachingSessionStatus.MASTERED
                    ? TeachingNextAction.COMPLETE : TeachingNextAction.RECHECK;
        } else if (check != null && !checkPassed) {
            status = TeachingSessionStatus.NEEDS_REVIEW;
            nextAction = TeachingNextAction.RECHECK;
        } else if (check != null) {
            status = TeachingSessionStatus.IN_PROGRESS;
            nextAction = TeachingNextAction.PRACTICE;
        } else {
            status = TeachingSessionStatus.IN_PROGRESS;
            nextAction = TeachingNextAction.CHECK;
        }
        List<String> weakPoints = new ArrayList<>();
        if (check != null && check.review() != null) weakPoints.add(check.review().weakPoint());
        if (practice != null && practice.review() != null) weakPoints.add(practice.review().weakPoint());
        return new TeachingSessionSummary(LESSON_ID, current.sessionId, status, nextAction,
                checkScore, MAX_SCORE, check != null, checkPassed,
                practiceScore, MAX_SCORE, practice != null, practicePassed,
                score, MAX_SCORE * 2, score * 100 / (MAX_SCORE * 2), completedItems, 2,
                List.copyOf(weakPoints));
    }

    private int score(TeachingAttemptState attempt, String answer) {
        String normalized = answer.toLowerCase();
        int points = normalized.length() >= 20 ? 1 : 0;
        if (normalized.contains(attempt.topic.toLowerCase())) points++;
        int coveredConcepts = (int) CORE_CONCEPTS.stream()
                .filter(normalized::contains)
                .count();
        points += Math.min(2, coveredConcepts);
        if (normalized.contains("例如") || normalized.contains("比如") || normalized.contains("因为")) points++;
        return Math.min(MAX_SCORE, points);
    }

    private int practiceScore(TeachingAttemptState attempt, String answer) {
        String normalized = answer.toLowerCase();
        int points = normalized.length() >= 30 ? 1 : 0;
        if (normalized.contains(attempt.topic.toLowerCase())) points++;
        if (normalized.contains("目标") || normalized.contains("目的")) points++;
        if (normalized.contains("工具") || normalized.contains("检索") || normalized.contains("调用")) points++;
        if (normalized.contains("结果") || normalized.contains("上下文") || normalized.contains("决策")) points++;
        return Math.min(MAX_SCORE, points);
    }

    private String practiceFeedback(int score, boolean passed) {
        if (passed) return "你的实践方案已经把概念迁移到了具体任务，并说明了行动和结果之间的关系。";
        return "实践方案还缺少完整链路。请补充任务目标、行动方式，以及工具结果如何影响下一步。";
    }

    private TeachingReview buildPracticeReview(TeachingAttemptState attempt) {
        return new TeachingReview("实践链路不完整",
                "一个可执行的 Agent 方案至少需要说明目标、行动、工具或知识库结果，以及下一步决策。",
                "请围绕“" + attempt.topic + "”重新写一个包含目标、工具调用和结果处理的任务方案。");
    }

    private String feedback(int score, boolean passed) {
        if (score >= 5) return "回答覆盖了核心概念，也说明了概念之间的关系。可以继续做一道实践题。";
        if (passed) return "你已经抓住了主要概念。建议再补充一个例子或边界，帮助理解更加稳定。";
        return "回答还缺少关键概念或具体说明。请回到讲解内容，重点想想它的目标、工具和执行结果之间有什么关系。";
    }

    private TeachingReview buildReview(TeachingAttemptState attempt, String answer) {
        String normalized = answer.toLowerCase();
        String normalizedTopic = attempt.topic.toLowerCase();
        String weakPoint;
        String explanation;
        if (normalizedTopic.contains("rag") && !normalized.contains("检索") && !normalized.contains("知识库")) {
            weakPoint = "没有说明 RAG 的检索步骤";
            explanation = "RAG 的核心不是只让模型回答，而是先从知识库检索相关资料，再把资料放入上下文中生成有依据的回答。";
        } else if ((normalizedTopic.contains("tool") || normalizedTopic.contains("工具"))
                && !normalized.contains("结果") && !normalized.contains("上下文")) {
            weakPoint = "没有说明工具结果如何回到模型";
            explanation = "工具调用完成后，结果需要回到模型上下文中，模型才能根据新信息继续回答或决定下一步。";
        } else if (normalizedTopic.contains("agent") && !normalized.contains("工具") && !normalized.contains("调用")) {
            weakPoint = "没有说明 Agent 与工具调用的关系";
            explanation = "Agent 不只是生成文字，还可以根据目标选择工具；工具返回的结果会重新提供给模型，影响后续回答或行动。";
        } else if (normalizedTopic.contains("agent") && !normalized.contains("目标") && !normalized.contains("决策")) {
            weakPoint = "没有说明 Agent 为什么要进行决策";
            explanation = "Agent 的关键在于围绕目标判断下一步，而不是固定执行一次检索再生成回答。";
        } else {
            weakPoint = "核心概念之间的关系还不够完整";
            explanation = "请把定义、执行步骤和结果联系起来说明，而不是只给出一个名词或结论。";
        }
        return new TeachingReview(weakPoint, explanation,
                "请用一个具体例子重新描述“" + attempt.topic + "”的目标、行动和结果，并说明工具结果如何影响下一步。");
    }

    private String extractQuestion(String answer, String topic) {
        List<String> candidates = new ArrayList<>();
        for (String line : answer == null ? "".split("\\R") : answer.split("\\R")) {
            String cleaned = line.strip().replaceFirst("^#+\\s*", "")
                    .replaceFirst("^(理解检查问题|检查问题|理解检查)[：:]\\s*", "");
            if (QUESTION_MARK.matcher(cleaned).matches()) candidates.add(cleaned);
        }
        if (!candidates.isEmpty()) return candidates.get(candidates.size() - 1);
        return "请用自己的话解释“" + topic.strip() + "”的核心概念，并举出一个例子？";
    }

    private void cleanupExpired() {
        attemptStore.deleteExpired(Instant.now());
    }

    private String ownerKey(AppUser user) {
        return user.getId() == null ? "account:" + user.getAccount() : "id:" + user.getId();
    }

}
