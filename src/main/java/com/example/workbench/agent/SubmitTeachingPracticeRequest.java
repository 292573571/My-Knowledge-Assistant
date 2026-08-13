package com.example.workbench.agent;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SubmitTeachingPracticeRequest(
        @NotBlank(message = "workspaceId 不能为空")
        @Size(max = 120, message = "workspaceId 不能超过 120 个字符")
        String workspaceId,
        @NotBlank(message = "sessionId 不能为空")
        @Size(max = 64, message = "sessionId 不能超过 64 个字符")
        String sessionId,
        @NotBlank(message = "practiceId 不能为空")
        @Size(max = 64, message = "practiceId 不能超过 64 个字符")
        String practiceId,
        @NotBlank(message = "answer 不能为空")
        @Size(max = 4000, message = "answer 不能超过 4000 个字符")
        String answer
) {
}
