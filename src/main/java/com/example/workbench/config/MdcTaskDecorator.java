package com.example.workbench.config;

import java.util.Map;
import org.springframework.core.task.TaskDecorator;

public class MdcTaskDecorator implements TaskDecorator {

    @Override
    public Runnable decorate(Runnable runnable) {
        Map<String, String> captured = LoggingContext.snapshot();
        return () -> {
            Map<String, String> previous = LoggingContext.snapshot();
            try {
                LoggingContext.restore(captured);
                runnable.run();
            } finally {
                LoggingContext.restore(previous);
            }
        };
    }
}
