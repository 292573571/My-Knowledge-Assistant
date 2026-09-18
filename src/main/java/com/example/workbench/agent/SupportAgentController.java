package com.example.workbench.agent;

import com.example.workbench.auth.AppUser;
import com.example.workbench.auth.AuthFilter;
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
 * 使用客服 API：面向所有登录用户的只读帮助问答。
 */
@RestController
@RequestMapping("/api/agent/support")
public class SupportAgentController {

    private final SupportAgentService agentService;
    private final WorkspaceService workspaceService;

    public SupportAgentController(SupportAgentService agentService, WorkspaceService workspaceService) {
        this.agentService = agentService;
        this.workspaceService = workspaceService;
    }

    @PostMapping("/chat")
    public MaintenanceAgentResult chat(@Valid @RequestBody MaintenanceAgentRequest request,
                                       HttpServletRequest httpRequest) {
        AppUser user = (AppUser) httpRequest.getAttribute(AuthFilter.AUTHENTICATED_USER_ATTRIBUTE);
        if (user == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请先登录");
        return agentService.chat(user, workspaceService.access(user, request.workspaceId()), request.message());
    }
}
