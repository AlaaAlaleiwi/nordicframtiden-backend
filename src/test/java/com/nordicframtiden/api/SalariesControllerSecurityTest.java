package com.nordicframtiden.api;

import com.nordicframtiden.company.StaffShiftRepository;
import com.nordicframtiden.pharmacy.ScheduleShiftRepository;
import com.nordicframtiden.security.jwt.JwtService;
import com.nordicframtiden.security.model.AppUser;
import com.nordicframtiden.security.model.UserProfile;
import com.nordicframtiden.security.model.Role;
import com.nordicframtiden.security.repo.AppUserRepository;
import com.nordicframtiden.security.repo.UserProfileRepository;
import com.nordicframtiden.service.PayrollService;
import com.nordicframtiden.service.PayslipFreezeService;
import com.nordicframtiden.service.PayslipDeliveryService;
import com.nordicframtiden.service.SalaryAdjustmentService;
import com.nordicframtiden.service.model.NetSalaryResponse;
import com.nordicframtiden.settings.EmailService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(SalariesController.class)
@AutoConfigureMockMvc
@Import(SalariesControllerSecurityTest.MethodSecurityTestConfig.class)
class SalariesControllerSecurityTest {

    @TestConfiguration
    @EnableMethodSecurity
    static class MethodSecurityTestConfig {
    }

    @jakarta.annotation.Resource
    MockMvc mvc;

    @MockitoBean ScheduleShiftRepository shiftRepo;
    @MockitoBean StaffShiftRepository staffShiftRepo;
    @MockitoBean UserProfileRepository profileRepo;
    @MockitoBean AppUserRepository userRepo;
    @MockitoBean PayrollService payrollService;
    @MockitoBean PayslipFreezeService payslipFreezeService;
    @MockitoBean SalaryAdjustmentService adjustmentService;
    @MockitoBean EmailService emailService;
    @MockitoBean PayslipDeliveryService payslipDeliveryService;
    @MockitoBean JwtService jwtService;

    @Test
    @WithMockUser(roles = "USER")
    void ordinaryUserCannotReadOrChangePayrollRevisions() throws Exception {
        mvc.perform(get("/api/salaries/payslip/revisions").param("userId", "42").param("year", "2026").param("month", "8"))
            .andExpect(status().isForbidden());
        for (String action : List.of("finalize", "corrections")) {
            mvc.perform(post("/api/salaries/payslip/" + action).with(csrf())
                .param("userId", "42").param("year", "2026").param("month", "8")
                .contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());
        }
    }

    @Test
    @WithMockUser(username = "payroll-operator", authorities = "PERM_SALARIES")
    void finalizationUsesAuthenticatedActor() throws Exception {
        mvc.perform(post("/api/salaries/payslip/finalize").with(csrf())
            .param("userId", "42").param("year", "2026").param("month", "8"))
            .andExpect(status().isOk());
        verify(payslipFreezeService).finalizePayslip(42L, 2026, 8, "USER", "payroll-operator");
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void staleCorrectionReturnsConflict() throws Exception {
        when(payslipFreezeService.correct(eq(42L), eq(2026), eq(8), eq("USER"), any(), any()))
            .thenThrow(new com.nordicframtiden.service.PayslipConflictException("Reload the payslip"));
        mvc.perform(post("/api/salaries/payslip/corrections").with(csrf())
            .param("userId", "42").param("year", "2026").param("month", "8")
            .contentType("application/json").content("{}"))
            .andExpect(status().isConflict());
    }

    @Test
    @WithMockUser(roles = "USER")
    void userCannotReadAnotherUsersPayslip() throws Exception {
        mvc.perform(get("/api/salaries/payslip")
                .param("userId", "42").param("year", "2026").param("month", "8"))
            .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "USER")
    void userCannotReadCompanySalaryReports() throws Exception {
        mvc.perform(get("/api/salaries/month")
                .param("start", "2026-08-01T00:00:00Z")
                .param("end", "2026-09-01T00:00:00Z"))
            .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "USER")
    void userCannotReadAnotherUsersSalaryHistory() throws Exception {
        mvc.perform(get("/api/salaries/user/years").param("userId", "42"))
            .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "USER")
    void userCannotSendSalaryEmail() throws Exception {
        mvc.perform(post("/api/salaries/send-pdf-email").with(csrf())
                .contentType("application/json")
                .content("""
                    {
                      "userId": 42,
                      "year": 2026,
                      "month": 8,
                      "role": "USER",
                      "pdfBase64": "cGRm"
                    }
                    """))
            .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "alice", roles = "USER")
    void payslipReadyStatusReturnsNullMonthBeforeTheFirstDelivery() throws Exception {
        AppUser alice = mock(AppUser.class);
        when(alice.getId()).thenReturn(7L);
        when(userRepo.findByUsername("alice")).thenReturn(Optional.of(alice));
        when(payslipDeliveryService.readyDateFor(any())).thenReturn(java.time.LocalDate.of(2026, 9, 21));
        when(payslipDeliveryService.lastDelivered(7L, "USER")).thenReturn(Optional.empty());

        mvc.perform(get("/api/salaries/payslip/ready-status"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ready").isBoolean())
            .andExpect(jsonPath("$.lastDeliveredMonth").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void resendDeliversOnlyTheRequestedRow() throws Exception {
        when(payslipDeliveryService.resendAndDeliver(101L))
            .thenReturn(new PayslipDeliveryService.ResendResult(true, true));

        mvc.perform(post("/api/salaries/payslip/deliveries/101/resend").with(csrf()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.queued").value(true))
            .andExpect(jsonPath("$.sent").value(true));

        verify(payslipDeliveryService).resendAndDeliver(101L);
        verify(payslipDeliveryService, never()).deliverPending();
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void resendReportsWhenTheRowCouldNotBeRequeued() throws Exception {
        when(payslipDeliveryService.resendAndDeliver(101L))
            .thenReturn(new PayslipDeliveryService.ResendResult(false, false));

        mvc.perform(post("/api/salaries/payslip/deliveries/101/resend").with(csrf()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.queued").value(false))
            .andExpect(jsonPath("$.sent").value(false));

        verify(payslipDeliveryService).resendAndDeliver(101L);
        verify(payslipDeliveryService, never()).deliverPending();
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void queueMissingOnlyQueuesTheRequestedMonth() throws Exception {
        when(payslipDeliveryService.queueMissing(YearMonth.of(2026, 8))).thenReturn(2);

        mvc.perform(post("/api/salaries/payslip/deliveries/queue-missing").with(csrf())
                .param("year", "2026").param("month", "8"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.queued").value(2))
            .andExpect(jsonPath("$.sent").value(false));

        verify(payslipDeliveryService).queueMissing(YearMonth.of(2026, 8));
        verify(payslipDeliveryService, never()).deliverPending();
    }

    @Test
    @WithMockUser(username = "alice", roles = "USER")
    void userCanReadOwnPayslipWithoutSupplyingUserId() throws Exception {
        AppUser alice = mock(AppUser.class);
        when(alice.getId()).thenReturn(7L);
        when(userRepo.findByUsername("alice")).thenReturn(Optional.of(alice));
        when(payslipFreezeService.resolve(7L, 2026, 8, "USER")).thenReturn(payslip(7L));

        mvc.perform(get("/api/salaries/payslip/me")
                .param("year", "2026").param("month", "8"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.userId").value(7));
    }

    @Test
    @WithMockUser(username = "alice", roles = "USER")
    void userCanReadOwnSalaryHistoryWithoutSupplyingUserId() throws Exception {
        AppUser alice = mock(AppUser.class);
        when(alice.getId()).thenReturn(7L);
        when(userRepo.findByUsername("alice")).thenReturn(Optional.of(alice));
        when(shiftRepo.findInRange(any(), any(), eq(null), eq(7L))).thenReturn(List.of());

        mvc.perform(get("/api/salaries/me/years"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$").isArray());

        mvc.perform(get("/api/salaries/me/months").param("year", "2026"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$").isArray());

        mvc.perform(get("/api/salaries/me/month")
                .param("year", "2026").param("month", "8"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$").isArray());
    }

    @Test
    @WithMockUser(authorities = {"ROLE_STAFF", "PERM_PEOPLE"})
    void staffWithoutSalaryPermissionCannotReadReports() throws Exception {
        mvc.perform(get("/api/salaries/month")
                .param("start", "2026-08-01T00:00:00Z")
                .param("end", "2026-09-01T00:00:00Z"))
            .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(authorities = {"ROLE_STAFF", "PERM_SALARIES"})
    void staffWithSalaryPermissionCanReadReports() throws Exception {
        when(shiftRepo.findInRange(any(), any(), eq(null), eq(null))).thenReturn(List.of());

        mvc.perform(get("/api/salaries/month")
                .param("start", "2026-08-01T00:00:00Z")
                .param("end", "2026-09-01T00:00:00Z"))
            .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void monthlyTotalsIncludePayslipAdjustmentFields() throws Exception {
        // One shift line: 10 h × 200 kr = 2000 kr base cost.
        com.nordicframtiden.company.StaffShift shift =
            mock(com.nordicframtiden.company.StaffShift.class);
        com.nordicframtiden.security.model.AppUser user =
            mock(com.nordicframtiden.security.model.AppUser.class);
        when(user.getId()).thenReturn(42L);
        when(user.getUsername()).thenReturn("staffer");
        when(shift.getUser()).thenReturn(user);
        when(shift.getStartAt()).thenReturn(java.time.OffsetDateTime.parse("2026-08-03T08:00:00Z"));
        when(shift.getEndAt()).thenReturn(java.time.OffsetDateTime.parse("2026-08-03T18:00:00Z"));
        when(staffShiftRepo.findInRange(any(), any(), eq(null))).thenReturn(List.of(shift));
        UserProfile profile = new UserProfile();
        profile.setFullName("Staff Person");
        profile.setHourlyCost(new BigDecimal("200"));
        when(profileRepo.findSummariesByUserIdIn(List.of(42L))).thenReturn(List.of(profileSummary(42L, "Staff Person", new BigDecimal("200"), "HOURLY", null)));

        // One saved payslip adjustment field: +500 kr bonus for the same month.
        com.nordicframtiden.service.model.SalaryAdjustment bonus =
            new com.nordicframtiden.service.model.SalaryAdjustment();
        bonus.setUserId(42L);
        bonus.setYear(2026);
        bonus.setMonth(8);
        bonus.setName("Bonus");
        bonus.setAmount(new BigDecimal("500"));
        bonus.setTaxTreatment(com.nordicframtiden.service.model.SalaryAdjustment.TaxTreatment.ONE_TIME_TAXABLE);
        when(adjustmentService.forMonth(2026, 8)).thenReturn(List.of(bonus));

        mvc.perform(get("/api/salaries/month")
                .param("start", "2026-08-01T00:00:00Z")
                .param("end", "2026-09-01T00:00:00Z")
                .param("role", "STAFF"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].users[0].totalCost").value(2500.00))
            .andExpect(jsonPath("$[0].totalCost").value(2500.00))
            .andExpect(jsonPath("$[0].users[0].hourlyCost").value(250.00));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void monthlyTotalsIncludeSundayPremiumLikeThePayslipGross() throws Exception {
        com.nordicframtiden.pharmacy.ScheduleShift shift =
            mock(com.nordicframtiden.pharmacy.ScheduleShift.class);
        com.nordicframtiden.pharmacy.Pharmacy pharmacy =
            mock(com.nordicframtiden.pharmacy.Pharmacy.class);
        com.nordicframtiden.security.model.AppUser user =
            mock(com.nordicframtiden.security.model.AppUser.class);
        when(pharmacy.getId()).thenReturn(11L);
        when(pharmacy.getName()).thenReturn("Central pharmacy");
        when(user.getId()).thenReturn(42L);
        when(user.getUsername()).thenReturn("pharmacist");
        when(shift.getPharmacy()).thenReturn(pharmacy);
        when(shift.getUser()).thenReturn(user);
        // Sunday shift: payroll gross includes a 100% Sunday OB premium.
        when(shift.getStartAt()).thenReturn(java.time.OffsetDateTime.parse("2026-08-02T08:00:00Z"));
        when(shift.getEndAt()).thenReturn(java.time.OffsetDateTime.parse("2026-08-02T18:00:00Z"));
        when(shiftRepo.findInRange(any(), any(), eq(null), eq(null))).thenReturn(List.of(shift));
        UserProfile profile = new UserProfile();
        profile.setFullName("Pharmacist Person");
        profile.setHourlyCost(new BigDecimal("200"));
        when(profileRepo.findSummariesByUserIdIn(List.of(42L))).thenReturn(List.of(profileSummary(42L, "Pharmacist Person", new BigDecimal("200"), "HOURLY", null)));
        when(adjustmentService.forMonth(2026, 8)).thenReturn(List.of());

        mvc.perform(get("/api/salaries/month")
                .param("start", "2026-08-01T00:00:00Z")
                .param("end", "2026-09-01T00:00:00Z")
                .param("role", "USER"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].users[0].totalCost").value(4000.00))
            .andExpect(jsonPath("$[0].totalCost").value(4000.00));

        verify(profileRepo).findSummariesByUserIdIn(List.of(42L));
        verify(profileRepo, never()).findByUserId(42L);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void monthlyTotalsIncludeAdjustmentOnlyEmployees() throws Exception {
        com.nordicframtiden.security.model.AppUser user =
            mock(com.nordicframtiden.security.model.AppUser.class);
        when(user.getId()).thenReturn(43L);
        when(user.getRoles()).thenReturn(Set.of(Role.USER));
        when(userRepo.findById(43L)).thenReturn(Optional.of(user));
        when(shiftRepo.findInRange(any(), any(), eq(null), eq(null))).thenReturn(List.of());

        UserProfile profile = new UserProfile();
        profile.setFullName("Adjustment Only Employee");
        when(profileRepo.findByUserId(43L)).thenReturn(Optional.of(profile));
        when(userRepo.findById(43L)).thenReturn(Optional.of(user));
        com.nordicframtiden.service.model.SalaryAdjustment bonus =
            new com.nordicframtiden.service.model.SalaryAdjustment();
        bonus.setUserId(43L);
        bonus.setYear(2026);
        bonus.setMonth(8);
        bonus.setName("Bonus");
        bonus.setAmount(new BigDecimal("750"));
        bonus.setTaxTreatment(com.nordicframtiden.service.model.SalaryAdjustment.TaxTreatment.ONE_TIME_TAXABLE);
        when(adjustmentService.forMonth(2026, 8)).thenReturn(List.of(bonus));

        mvc.perform(get("/api/salaries/month")
                .param("start", "2026-08-01T00:00:00Z")
                .param("end", "2026-09-01T00:00:00Z")
                .param("role", "USER"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].pharmacyId").value(0))
            .andExpect(jsonPath("$[0].users[0].userId").value(43))
            .andExpect(jsonPath("$[0].users[0].totalCost").value(750.00))
            .andExpect(jsonPath("$[0].totalCost").value(750.00));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void monthlyTotalsStayUnchangedWithoutAdjustments() throws Exception {
        com.nordicframtiden.company.StaffShift shift =
            mock(com.nordicframtiden.company.StaffShift.class);
        com.nordicframtiden.security.model.AppUser user =
            mock(com.nordicframtiden.security.model.AppUser.class);
        when(user.getId()).thenReturn(42L);
        when(user.getUsername()).thenReturn("staffer");
        when(shift.getUser()).thenReturn(user);
        when(shift.getStartAt()).thenReturn(java.time.OffsetDateTime.parse("2026-08-03T08:00:00Z"));
        when(shift.getEndAt()).thenReturn(java.time.OffsetDateTime.parse("2026-08-03T18:00:00Z"));
        when(staffShiftRepo.findInRange(any(), any(), eq(null))).thenReturn(List.of(shift));
        UserProfile profile = new UserProfile();
        profile.setFullName("Staff Person");
        profile.setHourlyCost(new BigDecimal("200"));
        when(profileRepo.findSummariesByUserIdIn(List.of(42L))).thenReturn(List.of(profileSummary(42L, "Staff Person", new BigDecimal("200"), "HOURLY", null)));
        when(adjustmentService.forMonth(2026, 8)).thenReturn(List.of());

        mvc.perform(get("/api/salaries/month")
                .param("start", "2026-08-01T00:00:00Z")
                .param("end", "2026-09-01T00:00:00Z")
                .param("role", "STAFF"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].users[0].totalCost").value(2000.0))
            .andExpect(jsonPath("$[0].totalCost").value(2000.0));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void salaryEmailUsesStoredEmployeeIdentityInsteadOfCallerSuppliedRecipient() throws Exception {
        UserProfile profile = new UserProfile();
        profile.setEmail("employee@example.com");
        profile.setFullName("Stored Employee");
        when(profileRepo.findByUserId(42L)).thenReturn(Optional.of(profile));
        when(payslipFreezeService.resolve(42L, 2026, 8, "USER")).thenReturn(payslip(42L, new BigDecimal("2500")));
        when(emailService.sendSalaryPdfEmail(eq("employee@example.com"), eq("Stored Employee"), any(), eq("2026-08")))
            .thenReturn(true);

        mvc.perform(post("/api/salaries/send-pdf-email").with(csrf())
                .contentType("application/json")
                .content("""
                    {
                      "userId": 42,
                      "year": 2026,
                      "month": 8,
                      "role": "USER",
                      "email": "attacker@example.com",
                      "employeeName": "Attacker",
                      "pdfBase64": "cGRm"
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.recipient").value("employee@example.com"))
            .andExpect(jsonPath("$.employeeName").value("Stored Employee"));

        verify(emailService).sendSalaryPdfEmail(
            eq("employee@example.com"), eq("Stored Employee"), any(), eq("2026-08"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void salaryEmailSkipsZeroGrossPayslips() throws Exception {
        UserProfile profile = new UserProfile();
        profile.setEmail("employee@example.com");
        profile.setFullName("Stored Employee");
        when(profileRepo.findByUserId(42L)).thenReturn(Optional.of(profile));
        NetSalaryResponse zeroPayslip = payslip(42L, BigDecimal.ZERO);
        when(payslipFreezeService.resolve(42L, 2026, 8, "USER")).thenReturn(zeroPayslip);

        mvc.perform(post("/api/salaries/send-pdf-email").with(csrf())
                .contentType("application/json")
                .content("""
                    {
                      "userId": 42,
                      "year": 2026,
                      "month": 8,
                      "role": "USER",
                      "pdfBase64": "cGRm"
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.sent").value(false))
            .andExpect(jsonPath("$.reason").value("ZERO_SALARY"));

        verify(emailService, never()).sendSalaryPdfEmail(any(), any(), any(), any());
    }

    @Test
    @WithMockUser(username = "erik", roles = "STAFF")
    void staffEmployeeReadsTheirOwnPayslipFromStaffShifts() throws Exception {
        AppUser erik = new AppUser();
        erik.setId(9L);
        erik.setUsername("erik");
        erik.setRoles(Set.of(Role.STAFF));
        when(userRepo.findByUsername("erik")).thenReturn(Optional.of(erik));
        when(userRepo.findById(9L)).thenReturn(Optional.of(erik));
        when(payslipFreezeService.resolve(9L, 2026, 8, "STAFF")).thenReturn(payslip(9L));

        mvc.perform(get("/api/salaries/payslip/me").param("year", "2026").param("month", "8"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.userId").value(9));
        verify(payslipFreezeService, never()).resolve(9L, 2026, 8, "USER");
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void monthlyEmployeeKeepsTheirAdjustmentsInTheSalaryList() throws Exception {
        when(shiftRepo.findInRange(any(), any(), eq(null), eq(null))).thenReturn(List.of());
        UserProfileRepository.MonthlySalaryProfile monthly = new UserProfileRepository.MonthlySalaryProfile() {
            @Override public Long getUserId() { return 42L; }
            @Override public String getFullName() { return "Monthly Person"; }
            @Override public BigDecimal getMonthlySalary() { return new BigDecimal("28000"); }
        };
        when(profileRepo.findMonthlySalaryProfilesByRole(Role.USER)).thenReturn(List.of(monthly));
        com.nordicframtiden.service.model.SalaryAdjustment bonus =
            new com.nordicframtiden.service.model.SalaryAdjustment();
        bonus.setUserId(42L);
        bonus.setYear(2026);
        bonus.setMonth(8);
        bonus.setName("Bonus");
        bonus.setAmount(new BigDecimal("5000"));
        bonus.setTaxTreatment(com.nordicframtiden.service.model.SalaryAdjustment.TaxTreatment.REGULAR_TAXABLE);
        when(adjustmentService.forMonth(2026, 8)).thenReturn(List.of(bonus));
        AppUser person = new AppUser();
        person.setId(42L);
        person.setRoles(Set.of(Role.USER));
        when(userRepo.findById(42L)).thenReturn(Optional.of(person));

        mvc.perform(get("/api/salaries/month")
                .param("start", "2026-08-01T00:00:00+02:00")
                .param("end", "2026-09-01T00:00:00+02:00"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].users.length()").value(1))
            .andExpect(jsonPath("$[0].users[0].totalCost").value(33000.00));
    }

    @Test
    @WithMockUser(username = "alice", roles = "USER")
    void ownMonthViewUsesStockholmDaysAndMonths() throws Exception {
        AppUser alice = new AppUser();
        alice.setId(7L);
        alice.setUsername("alice");
        when(userRepo.findByUsername("alice")).thenReturn(Optional.of(alice));
        com.nordicframtiden.pharmacy.Pharmacy pharmacy = mock(com.nordicframtiden.pharmacy.Pharmacy.class);
        com.nordicframtiden.pharmacy.ScheduleShift shift = new com.nordicframtiden.pharmacy.ScheduleShift();
        shift.setUser(alice);
        shift.setPharmacy(pharmacy);
        // Oct 1, 00:30–08:30 in Stockholm, stored as UTC on Sep 30.
        shift.setStartAt(java.time.OffsetDateTime.parse("2026-09-30T22:30:00Z"));
        shift.setEndAt(java.time.OffsetDateTime.parse("2026-10-01T06:30:00Z"));
        shift.setHourlyCostSnapshot(new BigDecimal("200"));
        when(shiftRepo.findInRange(any(), any(), eq(null), eq(7L))).thenReturn(List.of(shift));

        mvc.perform(get("/api/salaries/me/month").param("year", "2026").param("month", "10"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].dayKey").value("2026-10-01"));
        mvc.perform(get("/api/salaries/me/month").param("year", "2026").param("month", "9"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get("/api/salaries/me/months").param("year", "2026"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].month").value(10));
    }

    private static UserProfileRepository.UserProfileSummary profileSummary(
            Long userId, String fullName, BigDecimal hourlyCost, String payType, BigDecimal monthlySalary) {
        return new UserProfileRepository.UserProfileSummary() {
            @Override public Long getUserId() { return userId; }
            @Override public String getFullName() { return fullName; }
            @Override public BigDecimal getHourlyCost() { return hourlyCost; }
            @Override public String getPayType() { return payType; }
            @Override public BigDecimal getMonthlySalary() { return monthlySalary; }
        };
    }

    private static NetSalaryResponse payslip(long userId) {
        return payslip(userId, BigDecimal.ZERO);
    }

    private static NetSalaryResponse payslip(long userId, BigDecimal grossSalary) {
        return new NetSalaryResponse(
            userId, "2026-08", BigDecimal.ZERO, BigDecimal.ZERO, grossSalary,
            2026, "0180", 30, 1, BigDecimal.ZERO, BigDecimal.ZERO);
    }
}
