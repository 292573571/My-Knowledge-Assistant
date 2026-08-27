package com.example.workbench.audit;

import com.example.workbench.auth.AdminAuthorizationService;
import com.example.workbench.auth.AppUser;
import com.example.workbench.auth.AuthFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import com.example.workbench.pagination.PageResponse;

@RestController
@RequestMapping("/api/audit-events")
public class AuditController {

    private final AdminAuthorizationService adminAuthorizationService;
    private final AuditService auditService;

    public AuditController(AdminAuthorizationService adminAuthorizationService, AuditService auditService) {
        this.adminAuthorizationService = adminAuthorizationService;
        this.auditService = auditService;
    }

    @GetMapping
    public Object list(@org.springframework.web.bind.annotation.RequestParam(required = false) Integer page,
                       @org.springframework.web.bind.annotation.RequestParam(required = false) Integer size,
                       HttpServletRequest request) {
        adminAuthorizationService.requireAdmin(user(request));
        List<AuditEventResponse> events = auditService.listAll();
        return page == null && size == null ? events : PageResponse.of(events, page, size);
    }

    @DeleteMapping
    public Map<String, Object> delete(HttpServletRequest request) {
        AppUser actor = user(request);
        adminAuthorizationService.requireSuperAdmin(actor);
        long deleted = auditService.purgeAll(actor, requestId(request));
        return Map.of("deleted", deleted);
    }

    private AppUser user(HttpServletRequest request) {
        return (AppUser) request.getAttribute(AuthFilter.AUTHENTICATED_USER_ATTRIBUTE);
    }

    private String requestId(HttpServletRequest request) {
        Object value = request.getAttribute(com.example.workbench.config.HttpRequestLoggingFilter.REQUEST_ID_ATTRIBUTE);
        return value == null ? "unknown" : value.toString();
    }
}
