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
        boolean enabled,
        String ownerPublicId
) {
    public static AiModelResponse from(AiModel entity) {
        return new AiModelResponse(entity.getId(), entity.getName(), entity.getModelType(),
                entity.getBaseUrl(), maskApiKey(entity.getApiKey()),
                entity.getModel(), entity.getTemperature(), entity.getTopP(), entity.getMaxOutputTokens(),
                entity.getRequestTimeoutMs(), entity.getFallbackModels(), entity.isDefault(), entity.isEnabled(),
                entity.getOwnerPublicId());
    }

    private static String maskApiKey(String apiKey) {
        if (apiKey == null || apiKey.length() <= 8) {
            return "****";
        }
        return apiKey.substring(0, 4) + "***" + apiKey.substring(apiKey.length() - 4);
    }
}
