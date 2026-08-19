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

    protected SystemLog() {}

    public SystemLog(Instant timestamp, String level, String logger, String thread, String message) {
        this.timestamp = timestamp;
        this.level = level;
        this.logger = logger;
        this.thread = thread;
        this.message = message;
    }

    public Long getId() { return id; }
    public Instant getTimestamp() { return timestamp; }
    public String getLevel() { return level; }
    public String getLogger() { return logger; }
    public String getThread() { return thread; }
    public String getMessage() { return message; }
}
