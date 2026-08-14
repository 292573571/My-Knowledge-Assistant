package com.example.workbench.learningassistant;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LearningAssistantCheckRequest(
        @NotBlank @Size(max = 120) String workspaceId,
        @NotBlank @Size(max = 64) String checkId,
        @NotBlank @Size(max = 4000) String answer,
        @Size(max = 100) String clientRequestId
) {
}
