package com.example.workbench.workspace;

import com.example.workbench.auth.AdminAuthorizationService;
import com.example.workbench.auth.AppUser;
import com.example.workbench.auth.AuthFilter;
import com.example.workbench.audit.AuditAction;
import com.example.workbench.audit.AuditOutcome;
import com.example.workbench.audit.AuditService;
import com.example.workbench.audit.AuditEventResponse;
import com.example.workbench.config.HttpRequestLoggingFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/workspaces")
public class WorkspaceController {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceController.class);
    private final WorkspaceService workspaceService;
    private final AdminAuthorizationService adminAuthorizationService;
    private final AuditService auditService;

    public WorkspaceController(WorkspaceService workspaceService, AdminAuthorizationService adminAuthorizationService,
                               AuditService auditService) {
        this.workspaceService = workspaceService;
        this.adminAuthorizationService = adminAuthorizationService;
        this.auditService = auditService;
    }

    @GetMapping
    public List<WorkspaceResponse> list(HttpServletRequest request) {
        return workspaceService.list(user(request));
    }

    @PostMapping("/org")
    @ResponseStatus(HttpStatus.CREATED)
    public WorkspaceResponse createOrg(@Valid @RequestBody CreateWorkspaceRequest body, HttpServletRequest request) {
        AppUser actor = user(request);
        try {
            WorkspaceResponse result = workspaceService.createOrg(actor, body);
            record(actor, result.id(), AuditAction.WORKSPACE_CREATE, "WORKSPACE", result.id(),
                    AuditOutcome.SUCCESS, "NONE", requestId(request));
            return result;
        } catch (RuntimeException exception) {
            recordFailure(actor, "unknown", AuditAction.WORKSPACE_CREATE, "WORKSPACE", "pending", exception, request);
            throw exception;
        }
    }

    @PostMapping("/team")
    @ResponseStatus(HttpStatus.CREATED)
    public WorkspaceResponse createTeam(@Valid @RequestBody CreateWorkspaceRequest body, HttpServletRequest request) {
        AppUser actor = user(request);
        try {
            WorkspaceResponse result = body.parentId() != null && !body.parentId().isBlank()
                    ? workspaceService.createTeamUnder(actor, body.parentId(), body)
                    : workspaceService.createTeam(actor, body);
            record(actor, result.id(), AuditAction.WORKSPACE_CREATE, "WORKSPACE", result.id(),
                    AuditOutcome.SUCCESS, "NONE", requestId(request));
            return result;
        } catch (RuntimeException exception) {
            recordFailure(actor, "unknown", AuditAction.WORKSPACE_CREATE, "WORKSPACE", "pending", exception, request);
            throw exception;
        }
    }

    @PostMapping("/public")
    @ResponseStatus(HttpStatus.CREATED)
    public WorkspaceResponse createPublic(@Valid @RequestBody CreateWorkspaceRequest body, HttpServletRequest request) {
        AppUser user = user(request);
        try {
            adminAuthorizationService.requireAdmin(user);
            WorkspaceResponse result = workspaceService.createPublic(user, body);
            record(user, result.id(), AuditAction.WORKSPACE_CREATE, "WORKSPACE", result.id(),
                    AuditOutcome.SUCCESS, "NONE", requestId(request));
            return result;
        } catch (RuntimeException exception) {
            recordFailure(user, "public", AuditAction.WORKSPACE_CREATE, "WORKSPACE", "pending", exception, request);
            throw exception;
        }
    }

    @GetMapping("/{workspaceId}/members")
    public List<WorkspaceMemberResponse> members(@PathVariable String workspaceId, HttpServletRequest request) {
        return workspaceService.members(user(request), workspaceId);
    }

    @PostMapping("/{workspaceId}/members")
    @ResponseStatus(HttpStatus.CREATED)
    public WorkspaceMemberResponse addMember(
            @PathVariable String workspaceId,
            @Valid @RequestBody AddWorkspaceMemberRequest body,
            HttpServletRequest request
    ) {
        AppUser actor = user(request);
        try {
            WorkspaceMemberResponse result = workspaceService.addMember(actor, workspaceId, body);
            record(actor, workspaceId, AuditAction.WORKSPACE_MEMBER_ADD, "USER", result.publicId(),
                    AuditOutcome.SUCCESS, "NONE", requestId(request));
            return result;
        } catch (RuntimeException exception) {
            recordFailure(actor, workspaceId, AuditAction.WORKSPACE_MEMBER_ADD, "USER", "pending", exception, request);
            throw exception;
        }
    }

    @PutMapping("/{workspaceId}/members/{memberPublicId}")
    public WorkspaceMemberResponse changeRole(
            @PathVariable String workspaceId,
            @PathVariable String memberPublicId,
            @Valid @RequestBody UpdateWorkspaceMemberRoleRequest body,
            HttpServletRequest request
    ) {
        AppUser actor = user(request);
        try {
            WorkspaceMemberResponse result = workspaceService.changeRole(actor, workspaceId, memberPublicId, body);
            record(actor, workspaceId, AuditAction.WORKSPACE_MEMBER_ROLE_CHANGE, "USER", memberPublicId,
                    AuditOutcome.SUCCESS, "NONE", requestId(request));
            return result;
        } catch (RuntimeException exception) {
            recordFailure(actor, workspaceId, AuditAction.WORKSPACE_MEMBER_ROLE_CHANGE, "USER", memberPublicId, exception, request);
            throw exception;
        }
    }

    @DeleteMapping("/{workspaceId}/members/{memberPublicId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeMember(
            @PathVariable String workspaceId,
            @PathVariable String memberPublicId,
            HttpServletRequest request
    ) {
        AppUser actor = user(request);
        try {
            workspaceService.removeMember(actor, workspaceId, memberPublicId);
            record(actor, workspaceId, AuditAction.WORKSPACE_MEMBER_REMOVE, "USER", memberPublicId,
                    AuditOutcome.SUCCESS, "NONE", requestId(request));
        } catch (RuntimeException exception) {
            recordFailure(actor, workspaceId, AuditAction.WORKSPACE_MEMBER_REMOVE, "USER", memberPublicId, exception, request);
            throw exception;
        }
    }

    @GetMapping("/{workspaceId}/audit-events")
    public Object auditEvents(@PathVariable String workspaceId,
                              @org.springframework.web.bind.annotation.RequestParam(required = false) Integer page,
                              @org.springframework.web.bind.annotation.RequestParam(required = false) Integer size,
                              HttpServletRequest request) {
        workspaceService.ownerAccess(user(request), workspaceId);
        List<AuditEventResponse> events = auditService.list(workspaceId);
        return page == null && size == null ? events : com.example.workbench.pagination.PageResponse.of(events, page, size);
    }

    private AppUser user(HttpServletRequest request) {
        return (AppUser) request.getAttribute(AuthFilter.AUTHENTICATED_USER_ATTRIBUTE);
    }

    private void recordFailure(AppUser actor, String workspaceId, AuditAction action, String resourceType,
                               String resourceId, RuntimeException exception, HttpServletRequest request) {
        record(actor, workspaceId, action, resourceType, resourceId, auditService.outcome(exception),
                auditService.reasonCode(exception), requestId(request));
    }

    private void record(AppUser actor, String workspaceId, AuditAction action, String resourceType,
                        String resourceId, AuditOutcome outcome, String reasonCode, String requestId) {
        try {
            auditService.record(actor, workspaceId, action, resourceType, resourceId, outcome, reasonCode, requestId);
        } catch (RuntimeException auditException) {
            log.error("Business audit persistence failed action={} outcome={}", action, outcome);
        }
    }

    private String requestId(HttpServletRequest request) {
        Object value = request.getAttribute(HttpRequestLoggingFilter.REQUEST_ID_ATTRIBUTE);
        return value == null ? "unknown" : value.toString();
    }
}
