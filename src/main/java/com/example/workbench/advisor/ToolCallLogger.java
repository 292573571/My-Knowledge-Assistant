package com.example.workbench.advisor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class ToolCallLogger {

    private static final Logger log = LoggerFactory.getLogger(ToolCallLogger.class);

    public void logToolCall(String toolName, String reason, double confidence) {
        log.info("Tool call: tool={}, reason={}, confidence={}", toolName, reason, confidence);
    }
}
