package com.nordicframtiden.admin.dashboard;

import com.nordicframtiden.security.jwt.JwtService;
import com.nordicframtiden.security.repo.AppUserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Method-security contract for the dashboard KPI endpoints: only admins may
 * read aggregates. Mirrors AdminManagementControllerSecurityTest.
 */
@WebMvcTest(AdminDashboardKpiController.class)
@AutoConfigureMockMvc
@Import(AdminDashboardKpiControllerSecurityTest.MethodSecurityTestConfig.class)
class AdminDashboardKpiControllerSecurityTest {

    @TestConfiguration
    @EnableMethodSecurity
    static class MethodSecurityTestConfig {
    }

    @jakarta.annotation.Resource
    MockMvc mvc;

    @MockitoBean
    AdminDashboardKpiService service;

    @MockitoBean
    AppUserRepository userRepository;

    @MockitoBean
    JwtService jwtService;

    @Test
    @WithMockUser(roles = "STAFF")
    void staffCannotReadDashboardKpis() throws Exception {
        mvc.perform(get("/api/admins/dashboard/kpis"))
            .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminCanReadDashboardKpis() throws Exception {
        when(service.compute("WEEK")).thenReturn(new KpiCatalog.DashboardKpiResponse(
                "WEEK", "Sep 28 – Oct 4", Map.of()));

        mvc.perform(get("/api/admins/dashboard/kpis"))
            .andExpect(status().isOk());
        mvc.perform(get("/api/admins/dashboard/kpis/catalog"))
            .andExpect(status().isOk());
    }

    @Test
    void anonymousIsUnauthorized() throws Exception {
        mvc.perform(get("/api/admins/dashboard/kpis"))
            .andExpect(status().isUnauthorized());
    }
}
