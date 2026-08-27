package com.example.workbench.learningassistant;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import java.time.Instant;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class LearningRequestCoordinator {
    private final LearningSessionEventRepository repository;
    private final ObjectMapper objectMapper;

    public LearningRequestCoordinator(LearningSessionEventRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public LearningSessionEventEntity claim(LearningSessionEntity session, String requestId,
                                             String eventType, String requestHash) {
        Instant now = Instant.now();
        repository.expireProcessing(session.getSessionId(), "EXPIRED", now);
        LearningSessionEventEntity existing = repository
                .findBySessionIdAndEventTypeAndClientRequestId(session.getSessionId(), eventType, requestId)
                .orElse(null);
        if (existing != null) {
            if (!existing.getRequestHash().equals(requestHash)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "clientRequestId 已用于不同的请求内容");
            }
            if ("SUCCEEDED".equals(existing.getStatus())) return existing;
            if ("PROCESSING".equals(existing.getStatus())
                    && existing.getProcessingExpiresAt() != null
                    && existing.getProcessingExpiresAt().isAfter(now)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "该请求正在处理中，请稍后重试");
            }
            existing.claim();
            return repository.saveAndFlush(existing);
        }
        LearningSessionEventEntity event = new LearningSessionEventEntity(UUID.randomUUID().toString(),
                session.getSessionId(), session.getUserId(), session.getWorkspaceId(), requestId,
                eventType, requestHash);
        return repository.saveAndFlush(event);
    }

    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public LearningAssistantResponse existing(String sessionId, String requestId,
                                              String eventType, String requestHash) {
        LearningSessionEventEntity event = repository
                .findBySessionIdAndEventTypeAndClientRequestId(sessionId, eventType, requestId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "当前学习会话正在处理另一条请求"));
        if (!event.getRequestHash().equals(requestHash)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "clientRequestId 已用于不同的请求内容");
        }
        if (!"SUCCEEDED".equals(event.getStatus()) || event.getPayloadJson() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "该请求正在处理中，请稍后重试");
        }
        return read(event.getPayloadJson());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void succeed(String eventId, long generation, LearningAssistantResponse response) {
        try {
            if (repository.succeed(eventId, generation, objectMapper.writeValueAsString(response)) == 0) {
                throw new IllegalStateException("学习请求租约已失效");
            }
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法保存学习请求响应", exception);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void abandon(String eventId, long generation) {
        repository.abandon(eventId, generation, "ABANDONED");
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void abandonProcessing(String sessionId) {
        repository.abandonProcessing(sessionId, "ABANDONED");
    }

    public LearningAssistantResponse replayAfterConflict(String sessionId, String requestId,
                                                         String eventType, String requestHash,
                                                         DataIntegrityViolationException ignored) {
        return existing(sessionId, requestId, eventType, requestHash);
    }

    private LearningAssistantResponse read(String payload) {
        try {
            return objectMapper.readValue(payload, LearningAssistantResponse.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法恢复学习请求响应", exception);
        }
    }
}
