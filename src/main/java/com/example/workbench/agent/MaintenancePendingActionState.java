package com.example.workbench.agent;

import java.time.Instant;

final class MaintenancePendingActionState {

    final String token;
    final String userId;
    final String workspaceId;
    final MaintenanceAction action;
    final String targetId;
    final String description;
    final Instant expiresAt;

    MaintenancePendingActionState(String token, String userId, String workspaceId,
                                  MaintenanceAction action, String targetId,
                                  String description, Instant expiresAt) {
        this.token = token;
        this.userId = userId;
        this.workspaceId = workspaceId;
        this.action = action;
        this.targetId = targetId;
        this.description = description;
        this.expiresAt = expiresAt;
    }
}
