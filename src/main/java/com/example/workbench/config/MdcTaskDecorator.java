package com.example.workbench.config;

import java.util.Map;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import org.springframework.core.task.TaskDecorator;

public class MdcTaskDecorator implements TaskDecorator {

    @Override
    public Runnable decorate(Runnable runnable) {
        Map<String, String> captured = LoggingContext.snapshot();
        Context traceContext = Context.current();
        return () -> {
            Map<String, String> previous = LoggingContext.snapshot();
            try (Scope ignored = traceContext.makeCurrent()) {
                LoggingContext.restore(captured);
                runnable.run();
            } finally {
                LoggingContext.restore(previous);
            }
        };
    }
}
