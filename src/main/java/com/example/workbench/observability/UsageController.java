package com.example.workbench.observability;

import com.example.workbench.auth.AdminAuthorizationService;
import com.example.workbench.auth.AppUser;
import com.example.workbench.auth.AuthFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/usage")
public class UsageController {

    private final UsageQueryService usageQueryService;
    private final AdminAuthorizationService adminAuthorizationService;

    public UsageController(UsageQueryService usageQueryService,
                           AdminAuthorizationService adminAuthorizationService) {
        this.usageQueryService = usageQueryService;
        this.adminAuthorizationService = adminAuthorizationService;
    }

    @GetMapping("/summary")
    public UsageQueryService.UsageSummary summary(@RequestParam(defaultValue = "30") int days,
                                                  HttpServletRequest request) {
        AppUser user = (AppUser) request.getAttribute(AuthFilter.AUTHENTICATED_USER_ATTRIBUTE);
        return usageQueryService.summary(user.getId(), days);
    }

    @GetMapping("/model-stats")
    public List<UsageQueryService.ModelStat> modelStats(@RequestParam(defaultValue = "30") int days,
                                                        @RequestParam(defaultValue = "self") String scope,
                                                        HttpServletRequest request) {
        AppUser user = (AppUser) request.getAttribute(AuthFilter.AUTHENTICATED_USER_ATTRIBUTE);
        if ("all".equals(scope)) {
            adminAuthorizationService.requireSuperAdmin(user);
            return usageQueryService.modelStatsAll(days);
        }
        return usageQueryService.modelStats(user.getId(), days);
    }
}
