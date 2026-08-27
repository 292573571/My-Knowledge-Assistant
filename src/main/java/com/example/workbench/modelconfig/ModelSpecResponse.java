package com.example.workbench.modelconfig;

public record ModelSpecResponse(
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
    public static ModelSpecResponse from(ModelSpec spec) {
        return spec == null ? null : new ModelSpecResponse(spec.name(), spec.baseUrl(), maskApiKey(spec.apiKey()),
                spec.model(), spec.temperature(), spec.topP(), spec.maxOutputTokens(), spec.requestTimeoutMs(),
                spec.fallbackModels());
    }

    private static String maskApiKey(String apiKey) {
        if (apiKey == null || apiKey.length() <= 8) {
            return "****";
        }
        return apiKey.substring(0, 4) + "***" + apiKey.substring(apiKey.length() - 4);
    }
}
