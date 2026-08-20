package com.example.workbench.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

@Configuration
public class LoggingContextConfig {

    public LoggingContextConfig(
            @Value("${app.logging.instance-id:${INSTANCE_ID:unknown}}") String instanceId,
            @Value("${app.logging.environment:${APP_ENVIRONMENT:development}}") String environment
    ) {
        LoggingContext.initializeDeployment(instanceId, environment);
    }
}
