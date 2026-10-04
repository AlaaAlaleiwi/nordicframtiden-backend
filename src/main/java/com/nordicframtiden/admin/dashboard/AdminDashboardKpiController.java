package com.nordicframtiden.admin.dashboard;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Aggregate KPI feed for the customizable admin dashboard.
 *
 * Security: admin-only, enforced both here and by the SecurityConfig rule
 * for /api/admins/** (this controller lives under that prefix).
 *
 * GDPR: the response shape is values-only (key → number + unit); the catalog
 * carries presentation metadata but no personal data.
 */
@RestController
@RequestMapping("/api/admins/dashboard")
public class AdminDashboardKpiController {

    private final AdminDashboardKpiService service;

    public AdminDashboardKpiController(AdminDashboardKpiService service) {
        this.service = service;
    }

    @GetMapping("/kpis")
    public KpiCatalog.DashboardKpiResponse kpis(
            @RequestParam(defaultValue = "WEEK") String period) {
        return service.compute(period);
    }

    @GetMapping("/kpis/catalog")
    public List<KpiCatalog.KpiDefinition> catalog() {
        return KpiCatalog.catalog();
    }
}
