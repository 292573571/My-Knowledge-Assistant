package com.example.workbench.auth;

import com.example.workbench.audit.AuditAction;
import com.example.workbench.audit.AuditOutcome;
import com.example.workbench.audit.AuditService;
import com.example.workbench.config.HttpRequestLoggingFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/users")
public class AdminUserController {

    private static final Logger log = LoggerFactory.getLogger(AdminUserController.class);
    private final AdminUserService userService;
    private final AuditService auditService;

    public AdminUserController(AdminUserService userService, AuditService auditService) {
        this.userService = userService;
        this.auditService = auditService;
    }

    @GetMapping
    public List<AdminUserResponse> list(HttpServletRequest request) {
        return userService.list(user(request));
    }

    @PutMapping("/{publicId}/role")
    public AdminUserResponse changeRole(@PathVariable String publicId,
                                        @Valid @RequestBody UpdateSystemRoleRequest body,
                                        HttpServletRequest request) {
        AppUser actor = user(request);
        try {
            AdminUserResponse response = userService.changeRole(actor, publicId, body);
            record(actor, publicId, AuditOutcome.SUCCESS, "NONE", request);
            return response;
        } catch (RuntimeException exception) {
            record(actor, publicId, auditService.outcome(exception), auditService.reasonCode(exception), request);
            throw exception;
        }
    }

    private void record(AppUser actor, String resourceId, AuditOutcome outcome, String reasonCode,
                        HttpServletRequest request) {
        try {
            Object requestId = request.getAttribute(HttpRequestLoggingFilter.REQUEST_ID_ATTRIBUTE);
            auditService.record(actor, "system", AuditAction.USER_ROLE_CHANGE, "USER", resourceId,
                    outcome, reasonCode, requestId == null ? "unknown" : requestId.toString());
        } catch (RuntimeException auditException) {
            log.error("Business audit persistence failed action={} outcome={}", AuditAction.USER_ROLE_CHANGE, outcome);
        }
    }

    private AppUser user(HttpServletRequest request) {
        return (AppUser) request.getAttribute(AuthFilter.AUTHENTICATED_USER_ATTRIBUTE);
    }
}
