package com.example.workbench.learningassistant;

import com.example.workbench.agent.SubmitTeachingCheckRequest;
import com.example.workbench.agent.SubmitTeachingPracticeRequest;
import com.example.workbench.agent.TeachingAgentRequest;
import com.example.workbench.agent.TeachingAgentResult;
import com.example.workbench.agent.TeachingCheckResponse;
import com.example.workbench.agent.TeachingCheckService;
import com.example.workbench.agent.TeachingPracticeResponse;
import com.example.workbench.agent.TeachingUserLevel;
import com.example.workbench.auth.AppUser;
import com.example.workbench.conversation.ConversationRequest;
import com.example.workbench.conversation.ConversationResponse;
import com.example.workbench.conversation.ConversationExecutionRegistry;
import com.example.workbench.conversation.ConversationService;
import com.example.workbench.workspace.WorkspaceAccessContext;
import com.example.workbench.workspace.WorkspaceService;
import com.example.workbench.workbench.WorkbenchChatRequest;
import com.example.workbench.workbench.WorkbenchChatResponse;
import java.util.List;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class LearningAssistantService {
    private static final Pattern GUIDED_INTENT = Pattern.compile(
            "(教我|讲解|解释(?:一下|一下子)?|带我学|开始学|学习一下|检查我|考考我|练习一下|做练习)");
    private final ConversationService conversationService;
    private final WorkspaceService workspaceService;
    private final com.example.workbench.workbench.WorkbenchChatService chatService;
    private final com.example.workbench.agent.TeachingAgentService teachingService;
    private final TeachingCheckService checkService;
    private final LearningSessionRepository sessionRepository;
    private final LearningRequestCoordinator requestCoordinator;

    public LearningAssistantService(ConversationService conversationService, WorkspaceService workspaceService,
                                    com.example.workbench.workbench.WorkbenchChatService chatService,
                                    com.example.workbench.agent.TeachingAgentService teachingService,
                                    TeachingCheckService checkService,
                                    LearningSessionRepository sessionRepository,
                                    LearningRequestCoordinator requestCoordinator) {
        this.conversationService = conversationService;
        this.workspaceService = workspaceService;
        this.chatService = chatService;
        this.teachingService = teachingService;
        this.checkService = checkService;
        this.sessionRepository = sessionRepository;
        this.requestCoordinator = requestCoordinator;
    }

    @Transactional
    public LearningAssistantSessionResponse createSession(AppUser user, LearningAssistantSessionRequest request) {
        WorkspaceAccessContext access = access(user, request.workspaceId());
        String sessionId = UUID.randomUUID().toString();
        String title = "新的学习会话";
        LearningMode requestedMode = request.mode() == null ? LearningMode.AUTO : request.mode();
        ConversationResponse conversation = conversationService.create(user, access.workspaceId(),
                new ConversationRequest(sessionId, title, requestedMode == LearningMode.CHAT ? "chat" : "rag"));
        sessionRepository.save(new LearningSessionEntity(sessionId, user.getId(), access.workspaceId(), conversation.id(),
                title, null, requestedMode, (request.userLevel() == null ? TeachingUserLevel.BEGINNER : request.userLevel()).name()));
        return detail(user, access.workspaceId(), sessionId);
    }

    public List<LearningAssistantSessionResponse> listSessions(AppUser user, String workspaceId) {
        WorkspaceAccessContext access = access(user, workspaceId);
        return sessionRepository.findByUserIdAndWorkspaceIdOrderByUpdatedAtDesc(user.getId(), access.workspaceId()).stream()
                .map(this::summary)
                .toList();
    }

    public LearningAssistantSessionResponse getSession(AppUser user, String sessionId, String workspaceId) {
        WorkspaceAccessContext access = access(user, workspaceId);
        requireSession(user, access.workspaceId(), sessionId);
        return detail(user, access.workspaceId(), sessionId);
    }

    public void validateSession(AppUser user, String sessionId, String workspaceId) {
        WorkspaceAccessContext access = access(user, workspaceId);
        requireSession(user, access.workspaceId(), sessionId);
    }

    public LearningAssistantResponse message(AppUser user, String sessionId, LearningAssistantMessageRequest request) {
        return message(user, sessionId, request, null);
    }

    public LearningAssistantResponse message(AppUser user, String sessionId, LearningAssistantMessageRequest request,
                                             ConversationExecutionRegistry.Execution execution) {
        WorkspaceAccessContext access = access(user, request.workspaceId());
        conversationService.messages(user, access.workspaceId(), sessionId);
        LearningIntent intent = resolveIntent(request);
        LearningMode mode = resolveMode(request, intent);
        LearningSessionEntity session = requireSession(user, access.workspaceId(), sessionId);
        String hash = hash(request.message(), mode.name(), request.normalizedUserLevel().name());
        return idempotent(session, request.clientRequestId(), "MESSAGE", hash, () -> {
            requireRunning(execution);
            if (mode == LearningMode.GUIDED || mode == LearningMode.REVIEW || mode == LearningMode.PRACTICE) {
                String topic = null;
                conversationService.recordUserMessage(user, access.workspaceId(), sessionId,
                        request.message().strip().substring(0, Math.min(request.message().strip().length(), 24)),
                        "teaching", request.message());
                TeachingAgentResult result = teachingService.chat(user, access,
                        new TeachingAgentRequest(access.workspaceId(), sessionId, topic,
                                request.normalizedUserLevel(), request.message()),
                        () -> execution != null && execution.isCancelled());
                requireRunning(execution);
                workspaceService.access(user, access.workspaceId());
                session.touch(mode, result.topic(), result.stage().name(), "ACTIVE");
                session.updatePreferences(request.message().strip(), request.normalizedUserLevel().name());
                sessionRepository.save(session);
                conversationService.recordAssistantMessage(user, access.workspaceId(), sessionId, "teaching",
                        result.answer(), result.sources(), result.traces());
                return LearningAssistantResponse.teaching(result, intent);
            }
            WorkbenchChatResponse result = chatService.chat(user,
                    new WorkbenchChatRequest(sessionId, "rag", access.workspaceId(), request.message()));
            requireRunning(execution);
            workspaceService.access(user, access.workspaceId());
            session.touch(LearningMode.CHAT, null, "CHAT", "ACTIVE");
            session.updatePreferences(request.message().strip(), request.normalizedUserLevel().name());
            sessionRepository.save(session);
            return LearningAssistantResponse.chat(sessionId, result);
        });
    }

    /**
     * 流式消息：教学讲解和普通问答都逐 token 输出，回答正文完成后才执行持久化收尾。
     */
    public LearningAssistantResponse streamMessage(AppUser user, String sessionId, LearningAssistantMessageRequest request,
                                                   Consumer<String> onToken,
                                                   ConversationExecutionRegistry.Execution execution) {
        WorkspaceAccessContext access = access(user, request.workspaceId());
        conversationService.messages(user, access.workspaceId(), sessionId);
        LearningIntent intent = resolveIntent(request);
        LearningMode mode = resolveMode(request, intent);
        LearningSessionEntity session = requireSession(user, access.workspaceId(), sessionId);
        String hash = hash(request.message(), mode.name(), request.normalizedUserLevel().name());
        return idempotent(session, request.clientRequestId(), "MESSAGE", hash, () -> {
            requireRunning(execution);
            if (mode == LearningMode.GUIDED || mode == LearningMode.REVIEW || mode == LearningMode.PRACTICE) {
                String topic = null;
                conversationService.recordUserMessage(user, access.workspaceId(), sessionId,
                        request.message().strip().substring(0, Math.min(request.message().strip().length(), 24)),
                        "teaching", request.message());
                TeachingAgentResult result = teachingService.streamChat(user, access,
                        new TeachingAgentRequest(access.workspaceId(), sessionId, topic,
                                request.normalizedUserLevel(), request.message()),
                        onToken,
                        () -> execution != null && execution.isCancelled());
                requireRunning(execution);
                workspaceService.access(user, access.workspaceId());
                session.touch(mode, result.topic(), result.stage().name(), "ACTIVE");
                session.updatePreferences(request.message().strip(), request.normalizedUserLevel().name());
                sessionRepository.save(session);
                conversationService.recordAssistantMessage(user, access.workspaceId(), sessionId, "teaching",
                        result.answer(), result.sources(), result.traces());
                return LearningAssistantResponse.teaching(result, intent);
            }
            WorkbenchChatResponse result = chatService.streamChat(user,
                    new WorkbenchChatRequest(sessionId, "rag", access.workspaceId(), request.message()), onToken);
            requireRunning(execution);
            workspaceService.access(user, access.workspaceId());
            session.touch(LearningMode.CHAT, null, "CHAT", "ACTIVE");
            session.updatePreferences(request.message().strip(), request.normalizedUserLevel().name());
            sessionRepository.save(session);
            return LearningAssistantResponse.chat(sessionId, result);
        });
    }

    public LearningAssistantResponse check(AppUser user, String sessionId, LearningAssistantCheckRequest request) {
        WorkspaceAccessContext access = access(user, request.workspaceId());
        LearningSessionEntity session = requireSession(user, access.workspaceId(), sessionId);
        return idempotent(session, request.clientRequestId(), "CHECK",
                hash(request.checkId(), request.answer()), () -> {
                    TeachingCheckResponse result = checkService.submit(user, access,
                            new SubmitTeachingCheckRequest(request.workspaceId(), sessionId,
                                    request.checkId(), request.answer()));
                    workspaceService.access(user, access.workspaceId());
                    conversationService.recordUserMessage(user, access.workspaceId(), sessionId,
                            "理解检查", "teaching", request.answer());
                    conversationService.recordAssistantMessage(user, access.workspaceId(), sessionId,
                            "teaching", result.feedback(), List.of(), List.of());
                    session.touch(LearningMode.REVIEW, result.topic(), result.stage().name(),
                            result.nextAction().name().equals("COMPLETE") ? "COMPLETED" : "ACTIVE");
                    sessionRepository.save(session);
                    return LearningAssistantResponse.check(sessionId, result);
                });
    }

    public LearningAssistantResponse practice(AppUser user, String sessionId, LearningAssistantPracticeRequest request) {
        WorkspaceAccessContext access = access(user, request.workspaceId());
        LearningSessionEntity session = requireSession(user, access.workspaceId(), sessionId);
        return idempotent(session, request.clientRequestId(), "PRACTICE",
                hash(request.practiceId(), request.answer()), () -> {
                    TeachingPracticeResponse result = checkService.submitPractice(user, access,
                            new SubmitTeachingPracticeRequest(request.workspaceId(), sessionId,
                                    request.practiceId(), request.answer()));
                    workspaceService.access(user, access.workspaceId());
                    conversationService.recordUserMessage(user, access.workspaceId(), sessionId,
                            "实践练习", "teaching", request.answer());
                    conversationService.recordAssistantMessage(user, access.workspaceId(), sessionId,
                            "teaching", result.feedback(), List.of(), List.of());
                    session.touch(LearningMode.PRACTICE, result.topic(), result.stage().name(),
                            result.nextAction().name().equals("COMPLETE") ? "COMPLETED" : "ACTIVE");
                    sessionRepository.save(session);
                    return LearningAssistantResponse.practice(sessionId, result);
                });
    }

    @Transactional
    public void deleteSession(AppUser user, String sessionId, String workspaceId) {
        WorkspaceAccessContext access = access(user, workspaceId);
        conversationService.delete(user, access.workspaceId(), sessionId);
        sessionRepository.findBySessionIdAndUserIdAndWorkspaceId(sessionId, user.getId(), access.workspaceId())
                .ifPresent(sessionRepository::delete);
    }

    public void stop(AppUser user, String sessionId, String workspaceId) {
        WorkspaceAccessContext access = access(user, workspaceId);
        LearningSessionEntity session = requireSession(user, access.workspaceId(), sessionId);
        conversationService.stop(user, access.workspaceId(), sessionId);
        requestCoordinator.abandonProcessing(session.getSessionId());
    }

    private LearningAssistantSessionResponse detail(AppUser user, String workspaceId, String sessionId) {
        LearningSessionEntity metadata = requireSession(user, workspaceId, sessionId);
        var messages = conversationService.messages(user, workspaceId, metadata.getConversationId());
        var progress = teachingProgress(user, workspaceId, sessionId);
        var pending = checkService.pendingActions(user, access(user, workspaceId), sessionId);
        return new LearningAssistantSessionResponse(sessionId, metadata.getTitle(), workspaceId,
                metadata.getMode(), metadata.getTopic(), metadata.getUserLevel(), progress,
                pending.check(), pending.practice(), messages, metadata.getUpdatedAt());
    }

    private LearningAssistantSessionResponse summary(LearningSessionEntity metadata) {
        return new LearningAssistantSessionResponse(metadata.getSessionId(), metadata.getTitle(),
                metadata.getWorkspaceId(), metadata.getMode(), metadata.getTopic(), metadata.getUserLevel(),
                null, null, null, List.of(), metadata.getUpdatedAt());
    }

    private com.example.workbench.agent.TeachingSessionSummary teachingProgress(AppUser user, String workspaceId, String sessionId) {
        try {
            return checkService.summary(user, access(user, workspaceId), sessionId);
        } catch (ResponseStatusException exception) {
            if (exception.getStatusCode() == HttpStatus.NOT_FOUND) return null;
            throw exception;
        }
    }

    private LearningIntent resolveIntent(LearningAssistantMessageRequest request) {
        if (request.intent() != null) return request.intent();
        String message = request.message().toLowerCase();
        // “我学过 X，还应该学什么”是学习路径咨询，应直接回答，不应误启动一节课程。
        if (message.contains("学过") || message.contains("学习了") || message.contains("还应该学")
                || message.contains("接下来学") || message.contains("下一步学")) {
            return LearningIntent.ANSWER;
        }
        if (GUIDED_INTENT.matcher(message).find()) {
            return LearningIntent.START_LESSON;
        }
        return LearningIntent.ANSWER;
    }

    private LearningMode resolveMode(LearningAssistantMessageRequest request, LearningIntent intent) {
        LearningMode mode = request.normalizedMode();
        if (mode != LearningMode.AUTO) return mode;
        return intent == LearningIntent.ANSWER ? LearningMode.CHAT : LearningMode.GUIDED;
    }

    private LearningMode mode(String value) {
        try {
            if ("teaching".equalsIgnoreCase(value)) return LearningMode.GUIDED;
            if ("rag".equalsIgnoreCase(value)) return LearningMode.AUTO;
            return value == null ? LearningMode.AUTO : LearningMode.valueOf(value.toUpperCase());
        } catch (IllegalArgumentException exception) {
            return LearningMode.AUTO;
        }
    }

    private LearningSessionEntity requireSession(AppUser user, String workspaceId, String sessionId) {
        return sessionRepository.findBySessionIdAndUserIdAndWorkspaceId(sessionId, user.getId(), workspaceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "学习会话不存在"));
    }

    private LearningAssistantResponse idempotent(LearningSessionEntity session, String clientRequestId,
                                                 String type, String requestHash,
                                                 Supplier<LearningAssistantResponse> operation) {
        if (clientRequestId == null || clientRequestId.isBlank()) return operation.get();
        String requestId = clientRequestId.strip();
        LearningSessionEventEntity event;
        try {
            event = requestCoordinator.claim(session, requestId, type, requestHash);
        } catch (DataIntegrityViolationException exception) {
            return requestCoordinator.replayAfterConflict(session.getSessionId(), requestId,
                    type, requestHash, exception);
        }
        try {
            LearningAssistantResponse response = operation.get();
            requestCoordinator.succeed(event.getEventId(), response);
            return response;
        } catch (RuntimeException exception) {
            requestCoordinator.abandon(event.getEventId());
            throw exception;
        }
    }

    private void requireRunning(ConversationExecutionRegistry.Execution execution) {
        if (execution != null && execution.isCancelled()) throw new CancellationException("学习请求已停止");
    }

    private String hash(String... values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String value : values) {
                digest.update((value == null ? "" : value.strip()).getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    private WorkspaceAccessContext access(AppUser user, String workspaceId) {
        return workspaceService.access(user, workspaceId);
    }
}
