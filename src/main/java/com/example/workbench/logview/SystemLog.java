package com.example.workbench.logview;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "system_log")
public class SystemLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Instant timestamp;

    @Column(length = 8, nullable = false)
    private String level;

    @Column(length = 200, nullable = false)
    private String logger;

    @Column(length = 80, nullable = false)
    private String thread;

    @Column(nullable = false, columnDefinition = "TEXT")
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
