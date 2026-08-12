package com.example.workbench.agent;

import com.example.workbench.auth.AppUser;
import com.example.workbench.learning.LearningRecordService;
import com.example.workbench.workspace.WorkspaceAccessContext;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
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
    private static final Pattern QUESTION_MARK = Pattern.compile(".*[?？].*");
    private static final List<String> CORE_CONCEPTS = List.of(
            "目标", "工具", "检索", "上下文", "知识库", "模型", "步骤", "结果", "决策", "调用");

    private final LearningRecordService learningRecordService;
    private final Map<String, PendingAttempt> attempts = new ConcurrentHashMap<>();

    public TeachingCheckService(LearningRecordService learningRecordService) {
        this.learningRecordService = learningRecordService;
    }

    public TeachingCheckPrompt createPending(AppUser user, WorkspaceAccessContext access,
                                             String sessionId, String topic, String answer) {
        cleanupExpired();
        String ownerKey = ownerKey(user);
        long activeCount = attempts.values().stream()
                .filter(attempt -> attempt.ownerKey.equals(ownerKey) && !attempt.completed)
                .count();
        if (activeCount >= MAX_ACTIVE_ATTEMPTS) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "教学检查次数过多，请先完成已有课程。");
        }

        String question = extractQuestion(answer, topic);
        String checkId = UUID.randomUUID().toString();
        attempts.put(checkId, new PendingAttempt(checkId, ownerKey, access.workspaceId(), sessionId,
                topic, question, Instant.now().plus(ATTEMPT_TTL)));
        return new TeachingCheckPrompt(checkId, question);
    }

    public TeachingCheckResponse submit(AppUser user, WorkspaceAccessContext access,
                                        SubmitTeachingCheckRequest request) {
        cleanupExpired();
        PendingAttempt attempt = attempts.get(request.checkId().strip());
        if (attempt == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "教学检查不存在或已过期");
        }

        synchronized (attempt) {
            requireOwnerAndContext(user, access, request, attempt);
            String submittedAnswer = request.answer().strip();
            if (attempt.completed) {
                if (!attempt.answer.equals(submittedAnswer)) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "该检查题已经提交过不同答案");
                }
                return attempt.response;
            }

            int score = score(attempt, submittedAnswer);
            boolean passed = score >= PASS_SCORE;
            String feedback = feedback(score, passed);
            String recordDate;
            boolean saved = true;
            try {
                recordDate = learningRecordService.recordTeachingCheck(user, attempt.checkId, attempt.topic,
                        attempt.question, submittedAnswer, score, MAX_SCORE, passed, feedback);
            } catch (RuntimeException exception) {
                saved = false;
                recordDate = null;
            }
            attempt.answer = submittedAnswer;
            attempt.completed = true;
            attempt.response = new TeachingCheckResponse(attempt.checkId, attempt.sessionId, attempt.topic,
                    TeachingStage.CHECK, passed ? TeachingNextAction.PRACTICE : TeachingNextAction.REVIEW,
                    score, MAX_SCORE, passed, feedback, saved, recordDate, false);
            return attempt.response;
        }
    }

    private void requireOwnerAndContext(AppUser user, WorkspaceAccessContext access,
                                        SubmitTeachingCheckRequest request, PendingAttempt attempt) {
        if (!attempt.ownerKey.equals(ownerKey(user))
                || !attempt.workspaceId.equals(access.workspaceId())
                || !attempt.sessionId.equals(request.sessionId().strip())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "教学检查不存在或不可访问");
        }
    }

    private int score(PendingAttempt attempt, String answer) {
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

    private String feedback(int score, boolean passed) {
        if (score >= 5) return "回答覆盖了核心概念，也说明了概念之间的关系。可以继续做一道实践题。";
        if (passed) return "你已经抓住了主要概念。建议再补充一个例子或边界，帮助理解更加稳定。";
        return "回答还缺少关键概念或具体说明。请回到讲解内容，重点想想它的目标、工具和执行结果之间有什么关系。";
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
        Instant now = Instant.now();
        attempts.entrySet().removeIf(entry -> entry.getValue().expiresAt.isBefore(now));
    }

    private String ownerKey(AppUser user) {
        return user.getId() == null ? "account:" + user.getAccount() : "id:" + user.getId();
    }

    private static final class PendingAttempt {
        private final String checkId;
        private final String ownerKey;
        private final String workspaceId;
        private final String sessionId;
        private final String topic;
        private final String question;
        private final Instant expiresAt;
        private String answer = "";
        private boolean completed;
        private TeachingCheckResponse response;

        private PendingAttempt(String checkId, String ownerKey, String workspaceId, String sessionId,
                               String topic, String question, Instant expiresAt) {
            this.checkId = checkId;
            this.ownerKey = ownerKey;
            this.workspaceId = workspaceId;
            this.sessionId = sessionId;
            this.topic = topic;
            this.question = question;
            this.expiresAt = expiresAt;
        }
    }
}
