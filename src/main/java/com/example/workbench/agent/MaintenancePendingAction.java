package com.example.workbench.agent;

import java.time.Instant;

public record MaintenancePendingAction(String confirmationToken, MaintenanceAction action, String targetId,
                                       String description, Instant expiresAt) {
}
