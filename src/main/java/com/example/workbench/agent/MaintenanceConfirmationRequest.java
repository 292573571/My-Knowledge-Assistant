package com.example.workbench.agent;

import jakarta.validation.constraints.NotBlank;

public record MaintenanceConfirmationRequest(@NotBlank(message = "confirmationToken 不能为空") String confirmationToken,
                                             @NotBlank(message = "workspaceId 不能为空") String workspaceId) {
}
