package com.nordicframtiden.service;

import com.nordicframtiden.company.StaffScheduleService;
import com.nordicframtiden.pharmacy.ScheduleService;
import com.nordicframtiden.pharmacy.ScheduleShift;
import com.nordicframtiden.security.model.UserProfile;
import com.nordicframtiden.security.service.UserService;
import com.nordicframtiden.service.model.SalaryAdjustment;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PayrollServiceTest {

  @Test
  void monthlyEmployeeGetsFixedGrossRegardlessOfShifts() {
    UserService userService = mock(UserService.class);
    TaxService taxService = mock(TaxService.class);
    ScheduleService scheduleService = mock(ScheduleService.class);
    StaffScheduleService staffScheduleService = mock(StaffScheduleService.class);
    SalaryAdjustmentService adjustmentService = mock(SalaryAdjustmentService.class);
    OneTimeTaxService oneTimeTaxService = mock(OneTimeTaxService.class);
    PayrollService payrollService = new PayrollService(
        userService, taxService, scheduleService, staffScheduleService, adjustmentService, oneTimeTaxService);

    UserProfile profile = new UserProfile();
    profile.setPayType("MONTHLY");
    profile.setMonthlySalary(BigDecimal.valueOf(28000));
    profile.setYearOfBirth(1990);
    profile.setMunicipalityCode("0180");
    when(userService.getProfileByUserId(7L)).thenReturn(profile);

    // Two shifts (16 h) must NOT add shift pay — the gross stays the salary.
    ScheduleShift monday = new ScheduleShift();
    monday.setStartAt(OffsetDateTime.parse("2026-08-03T08:00:00Z"));
    monday.setEndAt(OffsetDateTime.parse("2026-08-03T16:00:00Z"));
    ScheduleShift tuesday = new ScheduleShift();
    tuesday.setStartAt(OffsetDateTime.parse("2026-08-04T08:00:00Z"));
    tuesday.setEndAt(OffsetDateTime.parse("2026-08-04T16:00:00Z"));
    when(scheduleService.listForUser(eq(7L), any(), any()))
        .thenReturn(List.of(monday, tuesday));
    when(adjustmentService.forMonth(7L, 2026, 8)).thenReturn(List.of());
    when(adjustmentService.annualOneTimeTotal(7L, 2026)).thenReturn(BigDecimal.ZERO);
    when(taxService.resolveTaxColumn(1990, 2026)).thenReturn(1);
    when(taxService.resolveTableNumber("0180", 2026)).thenReturn(30);
    when(taxService.lookupPreliminaryTax(2026, 30, 1, 28000)).thenReturn(43);

    var payslip = payrollService.netSalaryForUserMonth(7L, 2026, 8);

    assertEquals("MONTHLY", payslip.payType());
    assertEquals(new BigDecimal("28000.00"), payslip.monthlySalary());
    assertEquals(new BigDecimal("16.00"), payslip.totalHours()); // hours stay informational
    assertEquals(new BigDecimal("28000.00"), payslip.grossSalary());
    assertEquals(new BigDecimal("0.00"), payslip.saturdayOb());
    assertEquals(new BigDecimal("0.00"), payslip.sundayOb());
  }

  @Test
  void monthlyEmployeeWithNoShiftsStillGetsTheirSalary() {
    UserService userService = mock(UserService.class);
    TaxService taxService = mock(TaxService.class);
    ScheduleService scheduleService = mock(ScheduleService.class);
    StaffScheduleService staffScheduleService = mock(StaffScheduleService.class);
    SalaryAdjustmentService adjustmentService = mock(SalaryAdjustmentService.class);
    OneTimeTaxService oneTimeTaxService = mock(OneTimeTaxService.class);
    PayrollService payrollService = new PayrollService(
        userService, taxService, scheduleService, staffScheduleService, adjustmentService, oneTimeTaxService);

    UserProfile profile = new UserProfile();
    profile.setPayType("MONTHLY");
    profile.setMonthlySalary(BigDecimal.valueOf(28000));
    profile.setYearOfBirth(1990);
    profile.setMunicipalityCode("0180");
    when(userService.getProfileByUserId(7L)).thenReturn(profile);
    when(scheduleService.listForUser(eq(7L), any(), any())).thenReturn(List.of());
    when(adjustmentService.forMonth(7L, 2026, 8)).thenReturn(List.of());
    when(adjustmentService.annualOneTimeTotal(7L, 2026)).thenReturn(BigDecimal.ZERO);
    when(taxService.resolveTaxColumn(1990, 2026)).thenReturn(1);
    when(taxService.resolveTableNumber("0180", 2026)).thenReturn(30);
    when(taxService.lookupPreliminaryTax(2026, 30, 1, 28000)).thenReturn(43);

    var payslip = payrollService.netSalaryForUserMonth(7L, 2026, 8);

    assertEquals(new BigDecimal("0.00"), payslip.totalHours());
    assertEquals(new BigDecimal("28000.00"), payslip.grossSalary());
    assertEquals(new BigDecimal("43.00"), payslip.preliminaryTax());
  }

  @Test
  void monthlyPreviewUsesSalaryOverrideWithoutPersisting() {
    UserService userService = mock(UserService.class);
    TaxService taxService = mock(TaxService.class);
    ScheduleService scheduleService = mock(ScheduleService.class);
    StaffScheduleService staffScheduleService = mock(StaffScheduleService.class);
    SalaryAdjustmentService adjustmentService = mock(SalaryAdjustmentService.class);
    OneTimeTaxService oneTimeTaxService = mock(OneTimeTaxService.class);
    PayrollService payrollService = new PayrollService(
        userService, taxService, scheduleService, staffScheduleService, adjustmentService, oneTimeTaxService);

    UserProfile profile = new UserProfile();
    profile.setPayType("MONTHLY");
    profile.setMonthlySalary(BigDecimal.valueOf(28000)); // saved value must NOT be used
    profile.setYearOfBirth(1990);
    profile.setMunicipalityCode("0180");
    when(userService.getProfileByUserId(7L)).thenReturn(profile);
    when(scheduleService.listForUser(eq(7L), any(), any())).thenReturn(List.of());
    when(taxService.resolveTaxColumn(1990, 2026)).thenReturn(1);
    when(taxService.resolveTableNumber("0180", 2026)).thenReturn(30);
    when(taxService.lookupPreliminaryTax(2026, 30, 1, 30000)).thenReturn(43);
    when(adjustmentService.toEntities(List.of())).thenReturn(List.of());
    when(adjustmentService.annualOneTimeTotalExcludingMonth(7L, 2026, 8)).thenReturn(BigDecimal.ZERO);

    // The hourlyCost override slot carries the edited monthly salary.
    var preview = payrollService.previewForUserMonth(7L, 2026, 8, "USER", BigDecimal.valueOf(30000), List.of());

    assertEquals("MONTHLY", preview.payType());
    assertEquals(new BigDecimal("30000.00"), preview.monthlySalary());
    assertEquals(new BigDecimal("30000.00"), preview.grossSalary());
    verify(adjustmentService, never()).replace(eq(7L), eq(2026), eq(8), any());
  }

  @Test
  void monthWithoutShiftsReturnsZeroWithoutLookingUpTaxForZeroIncome() {
    UserService userService = mock(UserService.class);
    TaxService taxService = mock(TaxService.class);
    ScheduleService scheduleService = mock(ScheduleService.class);
    StaffScheduleService staffScheduleService = mock(StaffScheduleService.class);
    SalaryAdjustmentService adjustmentService = mock(SalaryAdjustmentService.class);
    OneTimeTaxService oneTimeTaxService = mock(OneTimeTaxService.class);
    PayrollService payrollService = new PayrollService(
        userService, taxService, scheduleService, staffScheduleService, adjustmentService, oneTimeTaxService);

    UserProfile profile = new UserProfile();
    profile.setHourlyCost(BigDecimal.valueOf(200));
    profile.setYearOfBirth(1990);
    profile.setMunicipalityCode("0180");
    when(userService.getProfileByUserId(7L)).thenReturn(profile);
    when(scheduleService.listForUser(eq(7L), any(), any())).thenReturn(List.of());
    when(adjustmentService.forMonth(7L, 2026, 8)).thenReturn(List.of());
    when(adjustmentService.annualOneTimeTotal(7L, 2026)).thenReturn(BigDecimal.ZERO);
    when(taxService.resolveTaxColumn(1990, 2026)).thenReturn(1);
    when(taxService.resolveTableNumber("0180", 2026)).thenReturn(30);

    var payslip = payrollService.netSalaryForUserMonth(7L, 2026, 8);

    assertEquals(new BigDecimal("0.00"), payslip.totalHours());
    assertEquals(new BigDecimal("0.00"), payslip.grossSalary());
    assertEquals(new BigDecimal("0.00"), payslip.preliminaryTax());
    assertEquals(new BigDecimal("0.00"), payslip.netSalary());
    verify(taxService, never()).lookupPreliminaryTax(anyInt(), anyInt(), anyInt(), anyInt());
  }

  @Test
  void pharmacistPayslipShowsSaturdayAndSundayObSeparately() {
    UserService userService = mock(UserService.class);
    TaxService taxService = mock(TaxService.class);
    ScheduleService scheduleService = mock(ScheduleService.class);
    StaffScheduleService staffScheduleService = mock(StaffScheduleService.class);
    SalaryAdjustmentService adjustmentService = mock(SalaryAdjustmentService.class);
    OneTimeTaxService oneTimeTaxService = mock(OneTimeTaxService.class);
    PayrollService payrollService = new PayrollService(
        userService, taxService, scheduleService, staffScheduleService, adjustmentService, oneTimeTaxService);

    UserProfile profile = new UserProfile();
    profile.setHourlyCost(BigDecimal.valueOf(200));
    profile.setYearOfBirth(1990);
    profile.setMunicipalityCode("0180");
    when(userService.getProfileByUserId(7L)).thenReturn(profile);

    ScheduleShift pharmacistShift = new ScheduleShift();
    pharmacistShift.setStartAt(OffsetDateTime.parse("2026-08-03T08:00:00Z"));
    pharmacistShift.setEndAt(OffsetDateTime.parse("2026-08-03T16:00:00Z"));
    ScheduleShift saturday = new ScheduleShift();
    saturday.setStartAt(OffsetDateTime.parse("2026-08-08T08:00:00Z"));
    saturday.setEndAt(OffsetDateTime.parse("2026-08-08T16:00:00Z"));
    ScheduleShift sunday = new ScheduleShift();
    sunday.setStartAt(OffsetDateTime.parse("2026-08-09T08:00:00Z"));
    sunday.setEndAt(OffsetDateTime.parse("2026-08-09T16:00:00Z"));
    when(scheduleService.listForUser(eq(7L), any(), any()))
        .thenReturn(List.of(pharmacistShift, saturday, sunday));
    when(adjustmentService.forMonth(7L, 2026, 8)).thenReturn(List.of());
    when(adjustmentService.annualOneTimeTotal(7L, 2026)).thenReturn(BigDecimal.ZERO);

    when(taxService.resolveTaxColumn(1990, 2026)).thenReturn(1);
    when(taxService.resolveTableNumber("0180", 2026)).thenReturn(30);
    when(taxService.lookupPreliminaryTax(2026, 30, 1, 7200)).thenReturn(0);

    var payslip = payrollService.netSalaryForUserMonth(7L, 2026, 8);

    assertEquals(new BigDecimal("24.00"), payslip.totalHours());
    assertEquals(new BigDecimal("4800.00"), payslip.baseHourlySalary());
    assertEquals(new BigDecimal("800.00"), payslip.saturdayOb());
    assertEquals(new BigDecimal("1600.00"), payslip.sundayOb());
    assertEquals(new BigDecimal("7200.00"), payslip.grossSalary());
    verify(staffScheduleService, never()).listForUser(eq(7L), any(), any());
  }

  @Test
  void previewUsesHourlyCostOverrideWithoutPersisting() {
    UserService userService = mock(UserService.class);
    TaxService taxService = mock(TaxService.class);
    ScheduleService scheduleService = mock(ScheduleService.class);
    StaffScheduleService staffScheduleService = mock(StaffScheduleService.class);
    SalaryAdjustmentService adjustmentService = mock(SalaryAdjustmentService.class);
    OneTimeTaxService oneTimeTaxService = mock(OneTimeTaxService.class);
    PayrollService payrollService = new PayrollService(
        userService, taxService, scheduleService, staffScheduleService, adjustmentService, oneTimeTaxService);

    UserProfile profile = new UserProfile();
    profile.setHourlyCost(BigDecimal.valueOf(200)); // saved value must NOT be used
    profile.setYearOfBirth(1990);
    profile.setMunicipalityCode("0180");
    when(userService.getProfileByUserId(7L)).thenReturn(profile);

    ScheduleShift monday = new ScheduleShift();
    monday.setStartAt(OffsetDateTime.parse("2026-08-03T08:00:00Z"));
    monday.setEndAt(OffsetDateTime.parse("2026-08-03T16:00:00Z"));
    ScheduleShift saturday = new ScheduleShift();
    saturday.setStartAt(OffsetDateTime.parse("2026-08-08T08:00:00Z"));
    saturday.setEndAt(OffsetDateTime.parse("2026-08-08T16:00:00Z"));
    ScheduleShift sunday = new ScheduleShift();
    sunday.setStartAt(OffsetDateTime.parse("2026-08-09T08:00:00Z"));
    sunday.setEndAt(OffsetDateTime.parse("2026-08-09T16:00:00Z"));
    when(scheduleService.listForUser(eq(7L), any(), any()))
        .thenReturn(List.of(monday, saturday, sunday));

    // 24 h * 1500 = 36000 base, +6000 Saturday OB, +12000 Sunday OB = 54000
    when(taxService.resolveTaxColumn(1990, 2026)).thenReturn(1);
    when(taxService.resolveTableNumber("0180", 2026)).thenReturn(30);
    when(taxService.lookupPreliminaryTax(2026, 30, 1, 54000)).thenReturn(43);
    when(adjustmentService.toEntities(List.of())).thenReturn(List.of());
    when(adjustmentService.annualOneTimeTotalExcludingMonth(7L, 2026, 8)).thenReturn(BigDecimal.ZERO);

    var preview = payrollService.previewForUserMonth(7L, 2026, 8, "USER", BigDecimal.valueOf(1500), List.of());

    assertEquals(new BigDecimal("1500.00"), preview.hourlyCost());
    assertEquals(new BigDecimal("36000.00"), preview.baseHourlySalary());
    assertEquals(new BigDecimal("6000.00"), preview.saturdayOb());
    assertEquals(new BigDecimal("12000.00"), preview.sundayOb());
    assertEquals(new BigDecimal("54000.00"), preview.grossSalary());
    assertEquals(new BigDecimal("43.00"), preview.preliminaryTax());
    assertEquals(new BigDecimal("53957.00"), preview.netSalary());
    verify(adjustmentService, never()).replace(eq(7L), eq(2026), eq(8), any());
    verify(adjustmentService, never()).forMonth(eq(7L), eq(2026), eq(8));
    verify(staffScheduleService, never()).listForUser(eq(7L), any(), any());
  }

  @Test
  void previewUsesAdjustmentOverridesAndSplitsRegularAndOneTimeTax() {
    UserService userService = mock(UserService.class);
    TaxService taxService = mock(TaxService.class);
    ScheduleService scheduleService = mock(ScheduleService.class);
    StaffScheduleService staffScheduleService = mock(StaffScheduleService.class);
    SalaryAdjustmentService adjustmentService = mock(SalaryAdjustmentService.class);
    OneTimeTaxService oneTimeTaxService = mock(OneTimeTaxService.class);
    PayrollService payrollService = new PayrollService(
        userService, taxService, scheduleService, staffScheduleService, adjustmentService, oneTimeTaxService);

    UserProfile profile = new UserProfile();
    profile.setHourlyCost(BigDecimal.valueOf(1500));
    profile.setYearOfBirth(1990);
    profile.setMunicipalityCode("0180");
    when(userService.getProfileByUserId(7L)).thenReturn(profile);

    ScheduleShift monday = new ScheduleShift();
    monday.setStartAt(OffsetDateTime.parse("2026-08-03T08:00:00Z"));
    monday.setEndAt(OffsetDateTime.parse("2026-08-03T16:00:00Z"));
    when(scheduleService.listForUser(eq(7L), any(), any())).thenReturn(List.of(monday));

    var inputs = List.of(
        new SalaryAdjustmentService.AdjustmentInput("Bonus", BigDecimal.valueOf(1000),
            SalaryAdjustment.TaxTreatment.REGULAR_TAXABLE, null, null, null, false),
        new SalaryAdjustmentService.AdjustmentInput("Milersättning", BigDecimal.valueOf(300),
            SalaryAdjustment.TaxTreatment.TAX_FREE, SalaryAdjustment.ReimbursementType.MILEAGE_OWN_CAR,
            BigDecimal.valueOf(100), null, true),
        new SalaryAdjustmentService.AdjustmentInput("Engångsbelopp", BigDecimal.valueOf(10000),
            SalaryAdjustment.TaxTreatment.ONE_TIME_TAXABLE, null, null, null, false));

    when(taxService.resolveTaxColumn(1990, 2026)).thenReturn(1);
    when(taxService.resolveTableNumber("0180", 2026)).thenReturn(30);
    // taxable base = 12000 + 1000 = 13000
    when(taxService.lookupPreliminaryTax(2026, 30, 1, 13000)).thenReturn(43);

    SalaryAdjustment regularEntity = new SalaryAdjustment();
    regularEntity.setName("Bonus");
    regularEntity.setAmount(BigDecimal.valueOf(1000));
    regularEntity.setTaxTreatment(SalaryAdjustment.TaxTreatment.REGULAR_TAXABLE);
    SalaryAdjustment taxFreeEntity = new SalaryAdjustment();
    taxFreeEntity.setName("Milersättning");
    taxFreeEntity.setAmount(BigDecimal.valueOf(300));
    taxFreeEntity.setTaxTreatment(SalaryAdjustment.TaxTreatment.TAX_FREE);
    taxFreeEntity.setReimbursementType(SalaryAdjustment.ReimbursementType.MILEAGE_OWN_CAR);
    taxFreeEntity.setQuantity(BigDecimal.valueOf(100));
    taxFreeEntity.setTaxFreeEligibilityConfirmed(true);
    SalaryAdjustment oneTimeEntity = new SalaryAdjustment();
    oneTimeEntity.setName("Engångsbelopp");
    oneTimeEntity.setAmount(BigDecimal.valueOf(10000));
    oneTimeEntity.setTaxTreatment(SalaryAdjustment.TaxTreatment.ONE_TIME_TAXABLE);
    when(adjustmentService.toEntities(inputs)).thenReturn(List.of(regularEntity, taxFreeEntity, oneTimeEntity));
    when(adjustmentService.taxFreePortion(regularEntity)).thenReturn(BigDecimal.ZERO);
    when(adjustmentService.taxFreePortion(taxFreeEntity)).thenReturn(BigDecimal.valueOf(300));
    when(adjustmentService.taxFreePortion(oneTimeEntity)).thenReturn(BigDecimal.ZERO);
    // projected = 13000 * 12 + 10000 = 166000
    when(adjustmentService.annualOneTimeTotalExcludingMonth(7L, 2026, 8)).thenReturn(BigDecimal.ZERO);
    when(oneTimeTaxService.rateFor(2026, 1, 166000)).thenReturn(52);

    var preview = payrollService.previewForUserMonth(7L, 2026, 8, "USER", null, inputs);

    // 12000 base + 1000 regular + 10000 one-time
    assertEquals(new BigDecimal("23000.00"), preview.grossSalary());
    assertEquals(new BigDecimal("43.00"), preview.regularTax());
    assertEquals(new BigDecimal("5200.00"), preview.oneTimeTax());
    assertEquals(new BigDecimal("5243.00"), preview.preliminaryTax());
    assertEquals(new BigDecimal("300.00"), preview.taxFreeAmount());
    assertEquals(new BigDecimal("18057.00"), preview.netSalary());
    verify(adjustmentService, never()).replace(eq(7L), eq(2026), eq(8), any());
  }

  @Test
  void nightShiftAcrossMonthBoundaryIsPaidOnceInTheMonthItStarts() {
    UserService userService = mock(UserService.class);
    TaxService taxService = mock(TaxService.class);
    ScheduleService scheduleService = mock(ScheduleService.class);
    StaffScheduleService staffScheduleService = mock(StaffScheduleService.class);
    SalaryAdjustmentService adjustmentService = mock(SalaryAdjustmentService.class);
    OneTimeTaxService oneTimeTaxService = mock(OneTimeTaxService.class);
    PayrollService payrollService = new PayrollService(
        userService, taxService, scheduleService, staffScheduleService, adjustmentService, oneTimeTaxService);

    UserProfile profile = new UserProfile();
    profile.setPayType("HOURLY");
    profile.setHourlyCost(BigDecimal.valueOf(200));
    profile.setYearOfBirth(1990);
    profile.setMunicipalityCode("0180");
    when(userService.getProfileByUserId(7L)).thenReturn(profile);

    // Sat 31 Oct 22:00 -> Sun 1 Nov 06:00 Stockholm (CET). The overlap query
    // returns it for both October and November.
    ScheduleShift night = new ScheduleShift();
    night.setStartAt(OffsetDateTime.parse("2026-10-31T22:00:00+01:00"));
    night.setEndAt(OffsetDateTime.parse("2026-11-01T06:00:00+01:00"));
    when(scheduleService.listForUser(eq(7L), any(), any())).thenReturn(List.of(night));
    when(adjustmentService.annualOneTimeTotal(eq(7L), anyInt())).thenReturn(BigDecimal.ZERO);
    when(taxService.resolveTaxColumn(1990, 2026)).thenReturn(1);
    when(taxService.resolveTableNumber("0180", 2026)).thenReturn(30);
    when(taxService.lookupPreliminaryTax(anyInt(), anyInt(), anyInt(), anyInt())).thenReturn(0);

    var october = payrollService.netSalaryForUserMonth(7L, 2026, 10);
    var november = payrollService.netSalaryForUserMonth(7L, 2026, 11);

    // 2 h Saturday (400 + 50% OB 200) + 6 h Sunday (1200 + 100% OB 1200).
    assertEquals(new BigDecimal("8.00"), october.totalHours());
    assertEquals(new BigDecimal("3000.00"), october.grossSalary());
    assertEquals(new BigDecimal("0.00"), november.totalHours());
    assertEquals(new BigDecimal("0.00"), november.grossSalary());
  }
}
