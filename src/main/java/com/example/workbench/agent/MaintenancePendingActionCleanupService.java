package com.example.workbench.agent;

import java.time.Instant;
import org.springframework.stereotype.Service;

@Service
public class MaintenancePendingActionCleanupService {

    private final MaintenancePendingActionStore actionStore;

    public MaintenancePendingActionCleanupService(MaintenancePendingActionStore actionStore) {
        this.actionStore = actionStore;
    }

    public long cleanupExpired(Instant now) {
        return actionStore.deleteExpired(now);
    }
}
