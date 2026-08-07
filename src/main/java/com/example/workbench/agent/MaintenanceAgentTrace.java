package com.example.workbench.agent;

/**
 * 一次工具调用的安全摘要，不保存完整文档正文或敏感参数。
 *
 * @param step 执行步骤
 * @param toolName 工具名称
 * @param status 执行状态
 * @param durationMs 执行耗时
 * @param resultSummary 结果摘要
 */
public record MaintenanceAgentTrace(int step, String toolName, String status, long durationMs, String resultSummary) {
}
