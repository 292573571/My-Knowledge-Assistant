package com.example.workbench.modelconfig;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record UserModelConfigRequest(
        @NotNull(message = "配置模式不能为空") UserModelMode mode,
        Long modelId,
        @Size(max = 64) String name,
        @Size(max = 256) String baseUrl,
        @Size(max = 256) String apiKey,
        @Size(max = 128) String model,
        Double temperature,
        Double topP,
        Integer maxOutputTokens,
        Long requestTimeoutMs,
        @Size(max = 256) String fallbackModels
) {
}
