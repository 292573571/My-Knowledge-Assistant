package com.example.workbench.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.ai")
public record AiConfig(
        String provider,
        String model
) {
}
