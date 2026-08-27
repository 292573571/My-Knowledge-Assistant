package com.example.workbench.conversation;

import com.example.workbench.auth.AppUser;
import com.example.workbench.auth.AuthFilter;
import com.example.workbench.workspace.WorkspaceAccessContext;
import com.example.workbench.workspace.WorkspaceService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;

@RestController
@RequestMapping("/api/conversations")
public class ConversationController {

    private final ConversationService conversationService;
    private final WorkspaceService workspaceService;

    public ConversationController(ConversationService conversationService, WorkspaceService workspaceService) {
        this.conversationService = conversationService;
        this.workspaceService = workspaceService;
    }

    @GetMapping
    public Object list(@RequestParam(required = false) String workspaceId,
                       @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size,
                       HttpServletRequest request) {
        AppUser user = user(request);
        String resolvedWorkspace = access(user, workspaceId).workspaceId();
        return page == null && size == null ? conversationService.list(user, resolvedWorkspace)
                : conversationService.page(user, resolvedWorkspace, page == null ? 0 : page, size == null ? 100 : size);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ConversationResponse create(@Valid @RequestBody ConversationRequest conversation,
                                       @RequestParam(required = false) String workspaceId, HttpServletRequest request) {
        AppUser user = user(request);
        return conversationService.create(user, access(user, workspaceId).workspaceId(), conversation);
    }

    @GetMapping("/{conversationId}/messages")
    public Object messages(@PathVariable String conversationId,
                           @RequestParam(required = false) String workspaceId,
                           @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size,
                           HttpServletRequest request) {
        AppUser user = user(request);
        String resolvedWorkspace = access(user, workspaceId).workspaceId();
        return page == null && size == null ? conversationService.messages(user, resolvedWorkspace, conversationId)
                : conversationService.pageMessages(user, resolvedWorkspace, conversationId, page == null ? 0 : page,
                size == null ? 100 : size);
    }

    @DeleteMapping("/{conversationId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String conversationId,
                       @RequestParam(required = false) String workspaceId, HttpServletRequest request) {
        AppUser user = user(request);
        conversationService.delete(user, access(user, workspaceId).workspaceId(), conversationId);
    }

    @PostMapping("/{conversationId}/stop")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void stop(@PathVariable String conversationId,
                     @RequestParam(required = false) String workspaceId, HttpServletRequest request) {
        AppUser user = user(request);
        conversationService.stop(user, access(user, workspaceId).workspaceId(), conversationId);
    }

    private AppUser user(HttpServletRequest request) {
        return (AppUser) request.getAttribute(AuthFilter.AUTHENTICATED_USER_ATTRIBUTE);
    }

    private WorkspaceAccessContext access(AppUser user, String workspaceId) {
        return workspaceService.access(user, workspaceId);
    }
}
