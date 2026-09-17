package com.example.workbench.logview;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.message.SimpleMessage;
import org.apache.logging.log4j.util.ReadOnlyStringMap;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;

/**
 * 业务日志界面读的是 system_log 表；第三方库(JDBC/ORM)的 ERROR 是数据库故障的唯一线索。
 * 这些用例锁住「第三方库 ERROR 必须入库」——此前被无条件丢弃，导致故障时日志界面里什么都看不到。
 */
class JpaLogAppenderTest {

    @Test
    void keepsThirdPartyErrorLogs() {
        SystemLogRepository repository = repository();

        JpaLogAppender.createAppender("Jpa", "true")
                .append(event(Level.ERROR, "org.postgresql.jdbc.PgConnection", new RuntimeException("connection reset")));

        verify(repository).save(any(SystemLog.class));
    }

    @Test
    void keepsSpringWarnAndErrorLogs() {
        SystemLogRepository repository = repository();

        JpaLogAppender.createAppender("Jpa", "true")
                .append(event(Level.ERROR, "org.springframework.transaction.support.TransactionTemplate", null));

        verify(repository).save(any(SystemLog.class));
    }

    @Test
    void stillDropsThirdPartyNoiseBelowError() {
        SystemLogRepository repository = repository();

        JpaLogAppender.createAppender("Jpa", "true")
                .append(event(Level.DEBUG, "org.hibernate.SQL", null));

        verify(repository, never()).save(any(SystemLog.class));
    }

    private SystemLogRepository repository() {
        SystemLogRepository repository = mock(SystemLogRepository.class);
        ApplicationContext context = mock(ApplicationContext.class);
        when(context.getBean(SystemLogRepository.class)).thenReturn(repository);
        JpaLogAppender.setApplicationContext(context);
        return repository;
    }

    private LogEvent event(Level level, String loggerName, Throwable thrown) {
        LogEvent event = mock(LogEvent.class);
        when(event.getLevel()).thenReturn(level);
        when(event.getLoggerName()).thenReturn(loggerName);
        when(event.getMessage()).thenReturn(new SimpleMessage("probe"));
        when(event.getTimeMillis()).thenReturn(System.currentTimeMillis());
        when(event.getThreadName()).thenReturn("test-thread");
        when(event.getThrown()).thenReturn(thrown);
        ReadOnlyStringMap contextData = mock(ReadOnlyStringMap.class);
        when(event.getContextData()).thenReturn(contextData);
        return event;
    }
}
