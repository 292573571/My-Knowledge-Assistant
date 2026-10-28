package com.example.workbench.observability;

import com.example.workbench.auth.AppUser;
import com.example.workbench.auth.AuthFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/usage")
public class UsageController {

    private final UsageQueryService usageQueryService;

    public UsageController(UsageQueryService usageQueryService) {
        this.usageQueryService = usageQueryService;
    }

    @GetMapping("/summary")
    public UsageQueryService.UsageSummary summary(@RequestParam(defaultValue = "30") int days,
                                                  HttpServletRequest request) {
        AppUser user = (AppUser) request.getAttribute(AuthFilter.AUTHENTICATED_USER_ATTRIBUTE);
        return usageQueryService.summary(user.getId(), days);
    }
}
