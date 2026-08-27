package com.example.workbench.modelconfig;

import java.util.List;

public record UserModelConfigResponse(
        UserModelMode mode,
        Long modelId,
        ModelSpecResponse custom,
        ModelSpecResponse resolved,
        List<AiModelResponse> poolModels,
        Long defaultModelId,
        Long defaultEmbeddingId,
        String defaultEmbeddingName,
        String defaultEmbeddingModel
) {
}
