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
import com.example.workbench.conversation.ConversationService;
import com.example.workbench.workspace.WorkspaceAccessContext;
import com.example.workbench.workspace.WorkspaceService;
import com.example.workbench.workbench.WorkbenchChatRequest;
import com.example.workbench.workbench.WorkbenchChatResponse;
import java.util.List;
import java.util.UUID;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class LearningAssistantService {
    private final ConversationService conversationService;
    private final WorkspaceService workspaceService;
    private final com.example.workbench.workbench.WorkbenchChatService chatService;
    private final com.example.workbench.agent.TeachingAgentService teachingService;
    private final TeachingCheckService checkService;
    private final LearningSessionRepository sessionRepository;
    private final LearningSessionEventRepository eventRepository;
    private final ObjectMapper objectMapper;

    public LearningAssistantService(ConversationService conversationService, WorkspaceService workspaceService,
                                    com.example.workbench.workbench.WorkbenchChatService chatService,
                                    com.example.workbench.agent.TeachingAgentService teachingService,
                                    TeachingCheckService checkService,
                                    LearningSessionRepository sessionRepository,
                                    LearningSessionEventRepository eventRepository,
                                    ObjectMapper objectMapper) {
        this.conversationService = conversationService;
        this.workspaceService = workspaceService;
        this.chatService = chatService;
        this.teachingService = teachingService;
        this.checkService = checkService;
        this.sessionRepository = sessionRepository;
        this.eventRepository = eventRepository;
        this.objectMapper = objectMapper;
    }

    public LearningAssistantSessionResponse createSession(AppUser user, LearningAssistantSessionRequest request) {
        WorkspaceAccessContext access = access(user, request.workspaceId());
        String sessionId = UUID.randomUUID().toString();
        String title = request.topic() == null || request.topic().isBlank() ? "新的学习会话" : request.topic().strip();
        LearningMode requestedMode = request.mode() == null ? LearningMode.AUTO : request.mode();
        ConversationResponse conversation = conversationService.create(user, access.workspaceId(),
                new ConversationRequest(sessionId, title, requestedMode == LearningMode.CHAT ? "chat" : "rag"));
        sessionRepository.save(new LearningSessionEntity(sessionId, user.getId(), access.workspaceId(), conversation.id(),
                title, request.topic(), requestedMode, (request.userLevel() == null ? TeachingUserLevel.BEGINNER : request.userLevel()).name()));
        return session(user, access.workspaceId(), conversation.id(), conversation.title(), requestedMode, request.topic(), conversation.updatedAt());
    }

    public List<LearningAssistantSessionResponse> listSessions(AppUser user, String workspaceId) {
        WorkspaceAccessContext access = access(user, workspaceId);
        return conversationService.list(user, access.workspaceId()).stream()
                .map(item -> session(user, access.workspaceId(), item.id(), item.title(), metadataMode(user, access.workspaceId(), item.id(), item.mode()),
                        metadataTopic(user, access.workspaceId(), item.id(), item.title()), item.updatedAt()))
                .toList();
    }

    public LearningAssistantSessionResponse getSession(AppUser user, String sessionId, String workspaceId) {
        WorkspaceAccessContext access = access(user, workspaceId);
        ConversationResponse conversation = conversationService.list(user, access.workspaceId()).stream()
                .filter(item -> item.id().equals(sessionId))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "学习会话不存在"));
        LearningSessionEntity metadata = sessionRepository.findBySessionIdAndUserIdAndWorkspaceId(sessionId, user.getId(), access.workspaceId())
                .orElse(null);
        return session(user, access.workspaceId(), sessionId, conversation.title(), metadata == null ? mode(conversation.mode()) : metadata.getMode(),
                metadata == null ? conversation.title() : metadata.getTopic(), conversation.updatedAt());
    }

    public LearningAssistantResponse message(AppUser user, String sessionId, LearningAssistantMessageRequest request) {
        WorkspaceAccessContext access = access(user, request.workspaceId());
        // 统一入口不得因为一个伪造的 sessionId 自动创建跨会话数据。
        conversationService.messages(user, access.workspaceId(), sessionId);
        LearningIntent intent = resolveIntent(request);
        LearningMode mode = resolveMode(request, intent);
        LearningSessionEntity session = requireSession(user, access.workspaceId(), sessionId);
        LearningAssistantResponse replay = replay(sessionId, request.clientRequestId());
        if (replay != null) return replay;
        if (mode == LearningMode.GUIDED || mode == LearningMode.REVIEW || mode == LearningMode.PRACTICE) {
            String topic = request.topic() == null || request.topic().isBlank() ? "当前问题" : request.topic().strip();
            conversationService.recordUserMessage(user, access.workspaceId(), sessionId,
                    topic.substring(0, Math.min(topic.length(), 24)), "teaching", request.message());
            TeachingAgentResult result = teachingService.chat(user, access, new TeachingAgentRequest(access.workspaceId(), sessionId,
                    topic, request.normalizedUserLevel(), request.message()));
            session.touch(mode, topic, result.stage().name(), "ACTIVE");
            sessionRepository.save(session);
            conversationService.recordAssistantMessage(user, access.workspaceId(), sessionId, "teaching",
                    result.answer(), result.sources(), result.traces());
            return remember(session, request.clientRequestId(), "MESSAGE", LearningAssistantResponse.teaching(result, intent));
        }
        WorkbenchChatResponse result = chatService.chat(user, new WorkbenchChatRequest(sessionId, "rag", access.workspaceId(), request.message()));
        session.touch(LearningMode.CHAT, null, "CHAT", "ACTIVE");
        sessionRepository.save(session);
        return remember(session, request.clientRequestId(), "MESSAGE", LearningAssistantResponse.chat(sessionId, result));
    }

    public LearningAssistantResponse check(AppUser user, String sessionId, LearningAssistantCheckRequest request) {
        WorkspaceAccessContext access = access(user, request.workspaceId());
        LearningSessionEntity session = requireSession(user, access.workspaceId(), sessionId);
        LearningAssistantResponse replay = replay(sessionId, request.clientRequestId());
        if (replay != null) return replay;
        TeachingCheckResponse result = checkService.submit(user, access,
                new SubmitTeachingCheckRequest(request.workspaceId(), sessionId, request.checkId(), request.answer()));
        conversationService.recordUserMessage(user, access.workspaceId(), sessionId, "理解检查", "teaching", request.answer());
        conversationService.recordAssistantMessage(user, access.workspaceId(), sessionId, "teaching", result.feedback(),
                List.of(), List.of());
        session.touch(LearningMode.REVIEW, result.topic(), result.stage().name(), result.nextAction().name().equals("COMPLETE") ? "COMPLETED" : "ACTIVE");
        sessionRepository.save(session);
        return remember(session, request.clientRequestId(), "CHECK", LearningAssistantResponse.check(sessionId, result));
    }

    public LearningAssistantResponse practice(AppUser user, String sessionId, LearningAssistantPracticeRequest request) {
        WorkspaceAccessContext access = access(user, request.workspaceId());
        LearningSessionEntity session = requireSession(user, access.workspaceId(), sessionId);
        LearningAssistantResponse replay = replay(sessionId, request.clientRequestId());
        if (replay != null) return replay;
        TeachingPracticeResponse result = checkService.submitPractice(user, access,
                new SubmitTeachingPracticeRequest(request.workspaceId(), sessionId, request.practiceId(), request.answer()));
        conversationService.recordUserMessage(user, access.workspaceId(), sessionId, "实践练习", "teaching", request.answer());
        conversationService.recordAssistantMessage(user, access.workspaceId(), sessionId, "teaching", result.feedback(),
                List.of(), List.of());
        session.touch(LearningMode.PRACTICE, result.topic(), result.stage().name(), result.nextAction().name().equals("COMPLETE") ? "COMPLETED" : "ACTIVE");
        sessionRepository.save(session);
        return remember(session, request.clientRequestId(), "PRACTICE", LearningAssistantResponse.practice(sessionId, result));
    }

    public void deleteSession(AppUser user, String sessionId, String workspaceId) {
        WorkspaceAccessContext access = access(user, workspaceId);
        conversationService.delete(user, access.workspaceId(), sessionId);
        sessionRepository.findBySessionIdAndUserIdAndWorkspaceId(sessionId, user.getId(), access.workspaceId())
                .ifPresent(sessionRepository::delete);
    }

    private LearningAssistantSessionResponse session(AppUser user, String workspaceId, String sessionId, String title,
                                                     LearningMode mode, String topic, java.time.Instant updatedAt) {
        var messages = conversationService.messages(user, workspaceId, sessionId);
        var progress = teachingProgress(user, workspaceId, sessionId);
        return new LearningAssistantSessionResponse(sessionId, title, workspaceId, mode, topic, progress, messages, updatedAt);
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
        if (message.contains("教我") || message.contains("讲解") || message.contains("学习") || message.contains("解释")) {
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
                .orElseGet(() -> {
                    // 兼容升级前已经存在的聊天会话，首次从统一入口使用时补建元数据。
                    LearningSessionEntity restored = new LearningSessionEntity(sessionId, user.getId(), workspaceId,
                            sessionId, "恢复的学习会话", null, LearningMode.AUTO, TeachingUserLevel.BEGINNER.name());
                    return sessionRepository.save(restored);
                });
    }

    private LearningAssistantResponse replay(String sessionId, String clientRequestId) {
        if (clientRequestId == null || clientRequestId.isBlank()) return null;
        return eventRepository.findBySessionIdAndClientRequestId(sessionId, clientRequestId.strip())
                .map(event -> read(event.getPayloadJson())).orElse(null);
    }

    private LearningAssistantResponse remember(LearningSessionEntity session, String clientRequestId, String type,
                                               LearningAssistantResponse response) {
        if (clientRequestId == null || clientRequestId.isBlank()) return response;
        try {
            eventRepository.save(new LearningSessionEventEntity(UUID.randomUUID().toString(), session.getSessionId(),
                    session.getUserId(), session.getWorkspaceId(), clientRequestId.strip(), type,
                    objectMapper.writeValueAsString(response)));
            return response;
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法保存学习请求事件", exception);
        }
    }

    private LearningAssistantResponse read(String payload) {
        try {
            return objectMapper.readValue(payload, LearningAssistantResponse.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法恢复学习请求事件", exception);
        }
    }

    private LearningMode metadataMode(AppUser user, String workspaceId, String sessionId, String fallback) {
        return sessionRepository.findBySessionIdAndUserIdAndWorkspaceId(sessionId, user.getId(), workspaceId)
                .map(LearningSessionEntity::getMode).orElse(mode(fallback));
    }

    private String metadataTopic(AppUser user, String workspaceId, String sessionId, String fallback) {
        return sessionRepository.findBySessionIdAndUserIdAndWorkspaceId(sessionId, user.getId(), workspaceId)
                .map(item -> item.getTopic() == null ? fallback : item.getTopic()).orElse(fallback);
    }

    private WorkspaceAccessContext access(AppUser user, String workspaceId) {
        return workspaceService.access(user, workspaceId);
    }
}
