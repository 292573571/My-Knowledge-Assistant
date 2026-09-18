package com.example.workbench.agent;

import com.example.workbench.auth.AdminAuthorizationService;
import com.example.workbench.auth.AppUser;
import com.example.workbench.auth.AuthFilter;
import com.example.workbench.workspace.WorkspaceAccessContext;
import com.example.workbench.workspace.WorkspaceService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 系统管家 API：面向管理员的系统级只读问答与待确认写操作。
 */
@RestController
@RequestMapping("/api/agent/system")
public class SystemAgentController {

    private final SystemAgentService agentService;
    private final WorkspaceService workspaceService;
    private final AdminAuthorizationService adminAuthorizationService;

    public SystemAgentController(SystemAgentService agentService, WorkspaceService workspaceService,
                                 AdminAuthorizationService adminAuthorizationService) {
        this.agentService = agentService;
        this.workspaceService = workspaceService;
        this.adminAuthorizationService = adminAuthorizationService;
    }

    @PostMapping("/chat")
    public MaintenanceAgentResult chat(@Valid @RequestBody MaintenanceAgentRequest request,
                                       HttpServletRequest httpRequest) {
        AppUser user = requireUser(httpRequest);
        adminAuthorizationService.requireAdmin(user);
        return agentService.chat(user, access(user, request.workspaceId()), request.message());
    }

    @PostMapping("/confirm")
    public MaintenanceWriteResult confirm(@Valid @RequestBody MaintenanceConfirmationRequest request,
                                          HttpServletRequest httpRequest) {
        AppUser user = requireUser(httpRequest);
        return agentService.confirm(user, access(user, request.workspaceId()), request.confirmationToken());
    }

    private static AppUser requireUser(HttpServletRequest request) {
        AppUser user = (AppUser) request.getAttribute(AuthFilter.AUTHENTICATED_USER_ATTRIBUTE);
        if (user == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请先登录");
        return user;
    }

    /** 超管可以维护自己并未加入的空间；普通管理员仍按成员关系解析。 */
    private WorkspaceAccessContext access(AppUser user, String workspaceId) {
        return adminAuthorizationService.isSuperAdmin(user)
                ? workspaceService.systemAccess(user, workspaceId)
                : workspaceService.access(user, workspaceId);
    }
}
