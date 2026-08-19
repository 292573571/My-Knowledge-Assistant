package com.example.workbench.logview;

import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Configuration;

@Configuration
public class JpaLogAppenderConfig {

    public JpaLogAppenderConfig(ApplicationContext applicationContext) {
        JpaLogAppender.setApplicationContext(applicationContext);
    }
}
