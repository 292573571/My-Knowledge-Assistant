package com.example.workbench.modelconfig;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AiModelRequest(
        @NotBlank(message = "模型名称不能为空") @Size(max = 64) String name,
        @NotBlank(message = "API 地址不能为空") @Size(max = 256) String baseUrl,
        @NotBlank(message = "API Key 不能为空") @Size(max = 256) String apiKey,
        @NotBlank(message = "模型标识不能为空") @Size(max = 128) String model,
        Double temperature,
        Double topP,
        Integer maxOutputTokens,
        Long requestTimeoutMs,
        @Size(max = 256) String fallbackModels,
        boolean isDefault,
        boolean enabled
) {
}
