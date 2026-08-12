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

@RestController
@RequestMapping("/api/agent/teaching")
public class TeachingAgentController {

    private final TeachingAgentService agentService;
    private final WorkspaceService workspaceService;

    public TeachingAgentController(TeachingAgentService agentService, WorkspaceService workspaceService) {
        this.agentService = agentService;
        this.workspaceService = workspaceService;
    }

    @PostMapping("/chat")
    public TeachingAgentResult chat(@Valid @RequestBody TeachingAgentRequest request,
                                    HttpServletRequest httpRequest) {
        AppUser user = (AppUser) httpRequest.getAttribute(AuthFilter.AUTHENTICATED_USER_ATTRIBUTE);
        if (user == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请先登录");
        return agentService.chat(user, workspaceService.access(user, request.workspaceId()), request);
    }
}
