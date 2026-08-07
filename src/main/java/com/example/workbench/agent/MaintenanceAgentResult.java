package com.example.workbench.agent;

import java.util.List;

/**
 * 维护 Agent 的对外响应。
 *
 * @param answer Agent 最终回答
 * @param traces 工具调用轨迹
 * @param steps 执行步骤数
 * @param readOnly 是否全程只读
 */
public record MaintenanceAgentResult(String answer, List<MaintenanceAgentTrace> traces, int steps, boolean readOnly) {
}
