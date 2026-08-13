package com.example.workbench.agent;

import java.time.Instant;
import org.springframework.stereotype.Service;

@Service
public class TeachingAttemptCleanupService {

    private final TeachingAttemptStore attemptStore;

    public TeachingAttemptCleanupService(TeachingAttemptStore attemptStore) {
        this.attemptStore = attemptStore;
    }

    public long cleanupExpired(Instant now) {
        return attemptStore.deleteExpired(now);
    }
}
