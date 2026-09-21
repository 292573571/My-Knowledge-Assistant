package com.example.workbench.agent;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record MaintenanceTaskReference(
        @NotBlank @Size(max = 64) String taskId,
        @NotBlank @Size(max = 120) String workspaceId
) {
}
