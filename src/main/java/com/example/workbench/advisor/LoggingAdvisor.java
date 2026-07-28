package com.example.workbench.advisor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class LoggingAdvisor {

    private static final Logger log = LoggerFactory.getLogger(LoggingAdvisor.class);

    public void beforeChat(String message) {
        log.info("Incoming chat message: {}", message);
    }

    public void afterChat(String response) {
        log.info("Outgoing chat response: {}", response);
    }
}
