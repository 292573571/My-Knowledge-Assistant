package com.example.workbench.modelconfig;

/** 一个可解析的模型规格，供模型池条目和用户自定义模型共用。 */
public record ModelSpec(
        String name,
        String baseUrl,
        String apiKey,
        String model,
        Double temperature,
        Double topP,
        Integer maxOutputTokens,
        Long requestTimeoutMs,
        String fallbackModels
) {
}
