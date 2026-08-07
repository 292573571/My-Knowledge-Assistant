package com.example.workbench.agent;

import com.example.workbench.auth.AppUser;
import com.example.workbench.workspace.WorkspaceAccessContext;

/**
 * 一次维护 Agent 执行期间固定的身份和空间权限上下文。
 *
 * @param user 当前登录用户
 * @param access 当前用户在目标空间中的访问权限
 */
public record MaintenanceAgentContext(AppUser user, WorkspaceAccessContext access) {
}
