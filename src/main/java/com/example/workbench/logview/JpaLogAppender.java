package com.example.workbench.logview;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import com.example.workbench.config.LoggingContext;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.plugins.Plugin;
import org.apache.logging.log4j.core.config.plugins.PluginAttribute;
import org.apache.logging.log4j.core.config.plugins.PluginFactory;
import org.springframework.context.ApplicationContext;

@Plugin(name = "Jpa", category = "Core", elementType = "appender", printObject = true)
public class JpaLogAppender extends AbstractAppender {

    private static volatile ApplicationContext applicationContext;
    private static volatile SystemLogRepository repository;
    private static volatile boolean repositoryChecked = false;

    public static void setApplicationContext(ApplicationContext ctx) {
        applicationContext = ctx;
        repositoryChecked = false;
        repository = null;
    }

    private JpaLogAppender(String name, Filter filter) {
        super(name, filter, null, true, null);
    }

    @PluginFactory
    public static JpaLogAppender createAppender(
            @PluginAttribute("name") String name,
            @PluginAttribute("ignoreExceptions") String ignoreExceptions
    ) {
        return new JpaLogAppender(name, null);
    }

    private void ensureRepository() {
        if (repositoryChecked) return;
        if (applicationContext == null) return;
        try {
            repository = applicationContext.getBean(SystemLogRepository.class);
            repositoryChecked = true;
        } catch (Exception e) {
            repository = null;
            repositoryChecked = false;
        }
    }

    @Override
    public void append(LogEvent event) {
        ensureRepository();
        if (repository == null) return;
        try {
            String level = event.getLevel().name();
            if (level.equals("TRACE")) return;
            String logger = event.getLoggerName();
            if (logger != null && logger.startsWith("org.springframework.") && !level.equals("WARN") && !level.equals("ERROR")) return;
            if (logger != null && (logger.startsWith("org.apache.")
                    || logger.startsWith("org.hibernate.")
                    || logger.startsWith("org.postgresql."))) return;
            String message = event.getMessage().getFormattedMessage();
            if (message != null && message.length() > 4000) {
                message = message.substring(0, 4000);
            }
            Instant ts = Instant.ofEpochMilli(event.getTimeMillis());
            String thread = event.getThreadName();
            if (thread != null && thread.length() > 80) thread = thread.substring(0, 80);
            if (logger != null && logger.length() > 200) logger = logger.substring(0, 200);
            Throwable thrown = event.getThrown();
            String exceptionType = null;
            String stackTrace = null;
            if (thrown != null) {
                exceptionType = thrown.getClass().getName();
                StringWriter writer = new StringWriter();
                thrown.printStackTrace(new PrintWriter(writer));
                stackTrace = writer.toString();
                if (stackTrace.length() > 32000) stackTrace = stackTrace.substring(0, 32000);
            }
            repository.save(new SystemLog(ts, level, logger != null ? logger : "", thread != null ? thread : "", message != null ? message : "",
                    context(event, LoggingContext.REQUEST_ID), context(event, LoggingContext.TRACE_ID),
                    context(event, LoggingContext.USER_ID), context(event, LoggingContext.WORKSPACE_ID),
                    context(event, LoggingContext.INSTANCE_ID), context(event, LoggingContext.ENVIRONMENT),
                    exceptionType, stackTrace));
        } catch (Exception ignored) {
        }
    }

    private String context(LogEvent event, String key) {
        String value = event.getContextData().getValue(key);
        if ((value == null || value.isBlank())
                && (LoggingContext.INSTANCE_ID.equals(key) || LoggingContext.ENVIRONMENT.equals(key))) {
            value = LoggingContext.deploymentValue(key);
        }
        return value == null || value.isBlank() ? null : value;
    }
}
