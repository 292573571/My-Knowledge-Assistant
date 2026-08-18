package com.example.workbench.modelconfig;

public record AiModelResponse(
        Long id,
        String name,
        AiModelType modelType,
        String baseUrl,
        String apiKey,
        String model,
        Double temperature,
        Double topP,
        Integer maxOutputTokens,
        Long requestTimeoutMs,
        String fallbackModels,
        boolean isDefault,
        boolean enabled
) {
    public static AiModelResponse from(AiModel entity) {
        return new AiModelResponse(entity.getId(), entity.getName(), entity.getModelType(),
                entity.getBaseUrl(), entity.getApiKey(),
                entity.getModel(), entity.getTemperature(), entity.getTopP(), entity.getMaxOutputTokens(),
                entity.getRequestTimeoutMs(), entity.getFallbackModels(), entity.isDefault(), entity.isEnabled());
    }
}
