package com.example.workbench.agent;

public record TeachingAgentTrace(int step, String toolName, String status, long durationMs) {
}
