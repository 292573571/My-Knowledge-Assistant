package com.example.workbench.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Optional;
import java.util.function.Function;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
class JpaTeachingAttemptStore implements TeachingAttemptStore {

    private final TeachingAttemptRepository repository;
    private final ObjectMapper objectMapper;

    JpaTeachingAttemptStore(TeachingAttemptRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public void save(TeachingAttemptState attempt) {
        TeachingAttemptEntity entity = repository.findById(attempt.checkId)
                .orElseGet(() -> new TeachingAttemptEntity(attempt, null, null));
        entity.update(attempt, json(attempt.response), json(attempt.practiceResponse));
        repository.save(entity);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<TeachingAttemptState> findByCheckId(String checkId) {
        return repository.findById(checkId).map(this::state);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<TeachingAttemptState> findByPracticeId(String practiceId) {
        return repository.findByPracticeId(practiceId).map(this::state);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<TeachingAttemptState> findLatest(String ownerKey, String workspaceId, String sessionId) {
        return repository.findFirstByOwnerKeyAndWorkspaceIdAndSessionIdOrderByCreatedAtDesc(ownerKey, workspaceId, sessionId)
                .map(this::state);
    }

    @Override
    @Transactional(readOnly = true)
    public long countActive(String ownerKey, Instant now) {
        return repository.countByOwnerKeyAndExpiresAtAfterAndCheckCompletedFalse(ownerKey, now)
                + repository.countByOwnerKeyAndExpiresAtAfterAndPracticeIdIsNotNullAndPracticeCompletedFalse(ownerKey, now);
    }

    @Override
    @Transactional
    public void delete(String checkId) {
        repository.deleteById(checkId);
    }

    @Override
    @Transactional
    public long deleteExpired(Instant now) {
        return repository.deleteByExpiresAtLessThanEqual(now);
    }

    @Override
    @Transactional
    public <T> T withCheckLock(String checkId, Function<TeachingAttemptState, T> operation) {
        TeachingAttemptEntity entity = repository.findByCheckIdForUpdate(checkId).orElse(null);
        if (entity == null) return operation.apply(null);
        TeachingAttemptState state = state(entity);
        T result = operation.apply(state);
        entity.update(state, json(state.response), json(state.practiceResponse));
        return result;
    }

    @Override
    @Transactional
    public <T> T withPracticeLock(String practiceId, Function<TeachingAttemptState, T> operation) {
        TeachingAttemptEntity entity = repository.findByPracticeIdForUpdate(practiceId).orElse(null);
        if (entity == null) return operation.apply(null);
        TeachingAttemptState state = state(entity);
        T result = operation.apply(state);
        entity.update(state, json(state.response), json(state.practiceResponse));
        return result;
    }

    private TeachingAttemptState state(TeachingAttemptEntity entity) {
        TeachingAttemptState state = new TeachingAttemptState(entity.checkId(), entity.ownerKey(), entity.workspaceId(),
                entity.sessionId(), entity.topic(), entity.question(), entity.expiresAt(), entity.createdAt());
        state.answer = entity.answer() == null ? "" : entity.answer();
        state.checkCompleted = entity.checkCompleted();
        state.response = value(entity.responseJson(), TeachingCheckResponse.class);
        state.practiceId = entity.practiceId();
        state.practiceQuestion = entity.practiceQuestion();
        state.practiceAnswer = entity.practiceAnswer() == null ? "" : entity.practiceAnswer();
        state.practiceCompleted = entity.practiceCompleted();
        state.practiceResponse = value(entity.practiceResponseJson(), TeachingPracticeResponse.class);
        return state;
    }

    private String json(Object value) {
        if (value == null) return null;
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to persist teaching attempt", exception);
        }
    }

    private <T> T value(String json, Class<T> type) {
        if (json == null) return null;
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to restore teaching attempt", exception);
        }
    }
}
