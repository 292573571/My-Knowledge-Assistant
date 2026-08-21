package com.example.workbench.learningassistant;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LearningAssistantPracticeRequest(
        @NotBlank @Size(max = 120) String workspaceId,
        @NotBlank @Size(max = 64) String practiceId,
        @NotBlank @Size(max = 4000) String answer,
        Long modelId,
        @NotBlank(message = "clientRequestId 不能为空") @Size(max = 100) String clientRequestId
) {
}
