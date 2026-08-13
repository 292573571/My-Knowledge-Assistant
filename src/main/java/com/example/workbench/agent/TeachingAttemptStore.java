package com.example.workbench.agent;

import java.time.Instant;
import java.util.Optional;
import java.util.function.Function;

interface TeachingAttemptStore {

    void save(TeachingAttemptState attempt);

    Optional<TeachingAttemptState> findByCheckId(String checkId);

    Optional<TeachingAttemptState> findByPracticeId(String practiceId);

    Optional<TeachingAttemptState> findLatest(String ownerKey, String workspaceId, String sessionId);

    long countActive(String ownerKey, Instant now);

    void delete(String checkId);

    long deleteExpired(Instant now);

    <T> T withCheckLock(String checkId, Function<TeachingAttemptState, T> operation);

    <T> T withPracticeLock(String practiceId, Function<TeachingAttemptState, T> operation);
}
