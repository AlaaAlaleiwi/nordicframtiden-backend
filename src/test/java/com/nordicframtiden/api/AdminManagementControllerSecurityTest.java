package com.nordicframtiden.api;

import com.nordicframtiden.admin.AdminService;
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

import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AdminManagementController.class)
@AutoConfigureMockMvc
@Import(AdminManagementControllerSecurityTest.MethodSecurityTestConfig.class)
class AdminManagementControllerSecurityTest {

    @TestConfiguration
    @EnableMethodSecurity
    static class MethodSecurityTestConfig {
    }

    @jakarta.annotation.Resource
    MockMvc mvc;

    @MockitoBean
    AdminService adminService;

    @MockitoBean
    AppUserRepository userRepository;

    @MockitoBean
    JwtService jwtService;

    @MockitoBean
    com.nordicframtiden.security.service.PasswordResetService passwordResetService;

    @Test
    @WithMockUser(roles = "STAFF")
    void staffCannotAccessAdministratorManagement() throws Exception {
        mvc.perform(get("/api/admins"))
            .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminCanListAdministrators() throws Exception {
        when(adminService.listAdminsDetailed()).thenReturn(List.of());

        mvc.perform(get("/api/admins"))
            .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void createWithMissingFieldsIsRejectedAs400BeforeReachingTheService() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/admins/create")
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
                .contentType("application/json")
                .content("{\"email\":\"not-an-email\",\"phone\":\"\"}"))
            .andExpect(status().isBadRequest());
        org.mockito.Mockito.verifyNoInteractions(adminService);
    }
}
