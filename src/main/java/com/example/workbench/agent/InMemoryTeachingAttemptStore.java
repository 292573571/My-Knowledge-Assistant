package com.example.workbench.agent;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.concurrent.ConcurrentHashMap;

final class InMemoryTeachingAttemptStore implements TeachingAttemptStore {

    private final Map<String, TeachingAttemptState> attempts = new ConcurrentHashMap<>();
    private final Map<String, Object> locks = new ConcurrentHashMap<>();

    @Override
    public void save(TeachingAttemptState attempt) {
        attempts.put(attempt.checkId, attempt);
    }

    @Override
    public Optional<TeachingAttemptState> findByCheckId(String checkId) {
        return Optional.ofNullable(attempts.get(checkId));
    }

    @Override
    public Optional<TeachingAttemptState> findByPracticeId(String practiceId) {
        return attempts.values().stream().filter(attempt -> practiceId.equals(attempt.practiceId)).findFirst();
    }

    @Override
    public Optional<TeachingAttemptState> findLatest(String ownerKey, String workspaceId, String sessionId) {
        return attempts.values().stream()
                .filter(attempt -> attempt.ownerKey.equals(ownerKey)
                        && attempt.workspaceId.equals(workspaceId)
                        && attempt.sessionId.equals(sessionId))
                .max(java.util.Comparator.comparing(attempt -> attempt.createdAt));
    }

    @Override
    public long countActive(String ownerKey, Instant now) {
        return attempts.values().stream()
                .filter(attempt -> attempt.ownerKey.equals(ownerKey) && attempt.expiresAt.isAfter(now)
                        && (!attempt.checkCompleted || (attempt.practiceId != null && !attempt.practiceCompleted)))
                .count();
    }

    @Override
    public void delete(String checkId) {
        attempts.remove(checkId);
    }

    @Override
    public long deleteExpired(Instant now) {
        long before = attempts.size();
        attempts.entrySet().removeIf(entry -> !entry.getValue().expiresAt.isAfter(now));
        return before - attempts.size();
    }

    @Override
    public <T> T withCheckLock(String checkId, Function<TeachingAttemptState, T> operation) {
        synchronized (locks.computeIfAbsent(checkId, ignored -> new Object())) {
            TeachingAttemptState attempt = attempts.get(checkId);
            if (attempt == null) return operation.apply(null);
            T result = operation.apply(attempt);
            attempts.put(checkId, attempt);
            return result;
        }
    }

    @Override
    public <T> T withPracticeLock(String practiceId, Function<TeachingAttemptState, T> operation) {
        TeachingAttemptState attempt = findByPracticeId(practiceId).orElse(null);
        return withCheckLock(attempt == null ? practiceId : attempt.checkId, operation);
    }
}
