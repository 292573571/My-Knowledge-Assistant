package com.example.workbench.agent;

import java.time.Instant;

public record MaintenancePendingAction(
        String confirmationToken,
        MaintenanceAction action,
        String targetId,
        String payload,
        String description,
        Instant expiresAt
) {
    public MaintenancePendingAction(String confirmationToken, MaintenanceAction action, String targetId,
                                    String description, Instant expiresAt) {
        this(confirmationToken, action, targetId, null, description, expiresAt);
    }
}
