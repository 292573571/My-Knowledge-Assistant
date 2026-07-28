package com.example.workbench.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.mcp")
public record McpConfig(
        boolean enabled,
        String serverUrl
) {
}
