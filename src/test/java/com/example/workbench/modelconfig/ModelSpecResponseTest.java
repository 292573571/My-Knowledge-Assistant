package com.example.workbench.modelconfig;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ModelSpecResponseTest {

    @Test
    void masksApiKeyWhileKeepingExistingModelSpecFields() {
        ModelSpecResponse response = ModelSpecResponse.from(new ModelSpec(
                "自定义模型", "https://api.example.com", "sk-secret-123456", "model-a",
                0.2, 0.8, 1024, 15000L, "fallback-a"));

        assertThat(response.name()).isEqualTo("自定义模型");
        assertThat(response.baseUrl()).isEqualTo("https://api.example.com");
        assertThat(response.apiKey()).isEqualTo("sk-s***3456");
        assertThat(response.model()).isEqualTo("model-a");
        assertThat(response.fallbackModels()).isEqualTo("fallback-a");
    }
}
