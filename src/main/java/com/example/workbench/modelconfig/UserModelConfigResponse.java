package com.example.workbench.modelconfig;

import java.util.List;

public record UserModelConfigResponse(
        UserModelMode mode,
        Long modelId,
        ModelSpec custom,
        ModelSpec resolved,
        List<AiModelResponse> poolModels,
        Long defaultModelId,
        Long defaultEmbeddingId
) {
}
