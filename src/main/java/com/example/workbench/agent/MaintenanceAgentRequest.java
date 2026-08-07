package com.example.workbench.agent;

import jakarta.validation.constraints.NotBlank;

/**
 * 维护 Agent 请求。
 */
public record MaintenanceAgentRequest(String workspaceId, @NotBlank(message = "message 不能为空") String message) {
}
