package com.example.workbench.agent;

import com.example.workbench.auth.AppUser;
import com.example.workbench.workspace.WorkspaceAccessContext;

/** 一次教学 Agent 请求中不可由模型修改的身份、空间和教学上下文。 */
public record TeachingAgentContext(
        AppUser user,
        WorkspaceAccessContext access,
        String sessionId,
        String topic,
        TeachingStage stage,
        TeachingUserLevel userLevel
) {
}
