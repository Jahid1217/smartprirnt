package com.smartprint.admin;

import com.smartprint.auth.AdminPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Shop-operator dashboard. Authentication (JWT roles) arrives with the identity
 * phase; until then this surface must not be exposed outside the shop network.
 */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminDashboardController {
    private final AdminDashboardService dashboardService;

    public AdminDashboardController(AdminDashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    @GetMapping("/dashboard")
    public AdminDashboardResponse dashboard(@AuthenticationPrincipal AdminPrincipal principal) {
        return dashboardService.dashboard(principal.shopId());
    }
}
