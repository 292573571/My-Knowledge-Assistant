package com.example.workbench.agent;

public record MaintenanceWriteResult(String answer, MaintenanceAction action, String taskId, boolean readOnly) {
}
