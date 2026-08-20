package com.example.workbench.logview;

import org.hibernate.annotations.Comment;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "system_log")
@Comment("系统日志")
public class SystemLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Comment("主键")
    private Long id;

    @Column(nullable = false)
    @Comment("日志时间")
    private Instant timestamp;

    @Column(length = 8, nullable = false)
    @Comment("日志级别")
    private String level;

    @Column(length = 200, nullable = false)
    @Comment("日志器名称")
    private String logger;

    @Column(length = 80, nullable = false)
    @Comment("线程名称")
    private String thread;

    @Column(nullable = false, columnDefinition = "TEXT")
    @Comment("日志消息")
    private String message;

    @Column(name = "request_id", length = 128)
    @Comment("请求标识")
    private String requestId;

    @Column(name = "trace_id", length = 128)
    @Comment("链路标识")
    private String traceId;

    @Column(name = "user_id", length = 64)
    @Comment("用户标识")
    private String userId;

    @Column(name = "workspace_id", length = 120)
    @Comment("知识空间标识")
    private String workspaceId;

    @Column(name = "instance_id", length = 128)
    @Comment("实例标识")
    private String instanceId;

    @Column(name = "environment", length = 32)
    @Comment("运行环境")
    private String environment;

    @Column(name = "exception_type", length = 255)
    @Comment("异常类型")
    private String exceptionType;

    @Column(name = "stack_trace", columnDefinition = "TEXT")
    @Comment("异常堆栈")
    private String stackTrace;

    protected SystemLog() {}

    public SystemLog(Instant timestamp, String level, String logger, String thread, String message) {
        this(timestamp, level, logger, thread, message, null, null, null, null, null, null, null, null);
    }

    public SystemLog(Instant timestamp, String level, String logger, String thread, String message,
                     String requestId, String traceId, String userId, String workspaceId,
                     String instanceId, String environment, String exceptionType, String stackTrace) {
        this.timestamp = timestamp;
        this.level = level;
        this.logger = logger;
        this.thread = thread;
        this.message = message;
        this.requestId = requestId;
        this.traceId = traceId;
        this.userId = userId;
        this.workspaceId = workspaceId;
        this.instanceId = instanceId;
        this.environment = environment;
        this.exceptionType = exceptionType;
        this.stackTrace = stackTrace;
    }

    public Long getId() { return id; }
    public Instant getTimestamp() { return timestamp; }
    public String getLevel() { return level; }
    public String getLogger() { return logger; }
    public String getThread() { return thread; }
    public String getMessage() { return message; }
    public String getRequestId() { return requestId; }
    public String getTraceId() { return traceId; }
    public String getUserId() { return userId; }
    public String getWorkspaceId() { return workspaceId; }
    public String getInstanceId() { return instanceId; }
    public String getEnvironment() { return environment; }
    public String getExceptionType() { return exceptionType; }
    public String getStackTrace() { return stackTrace; }
}
