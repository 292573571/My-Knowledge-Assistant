package com.example.workbench.agent;

public record TeachingAgentTrace(int step, String toolName, String status, long durationMs, String detail) {
    public TeachingAgentTrace(int step, String toolName, String status, long durationMs) {
        this(step, toolName, status, durationMs, null);
    }
}
