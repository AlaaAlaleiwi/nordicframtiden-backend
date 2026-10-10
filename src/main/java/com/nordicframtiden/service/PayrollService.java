package com.nordicframtiden.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.DayOfWeek;
import java.util.List;

import org.springframework.stereotype.Service;

import com.nordicframtiden.company.StaffScheduleService;
import com.nordicframtiden.pharmacy.ScheduleService;
import com.nordicframtiden.security.service.UserService;
import com.nordicframtiden.service.model.NetSalaryResponse;

@Service
public class PayrollService {
  private static final ZoneId STOCKHOLM = ZoneId.of("Europe/Stockholm");

  private final UserService userService;
  private final TaxService taxService;

  private final ScheduleService scheduleService;      // your existing
  private final StaffScheduleService staffScheduleService; // your existing
  private final SalaryAdjustmentService adjustmentService;
  private final OneTimeTaxService oneTimeTaxService;

    

  public PayrollService(UserService userService, TaxService taxService, ScheduleService scheduleService,
        StaffScheduleService staffScheduleService, SalaryAdjustmentService adjustmentService,
        OneTimeTaxService oneTimeTaxService) {
    this.userService = userService;
    this.taxService = taxService;
    this.scheduleService = scheduleService;
    this.staffScheduleService = staffScheduleService;
    this.adjustmentService = adjustmentService;
    this.oneTimeTaxService = oneTimeTaxService;
}
public NetSalaryResponse netSalaryForUserMonth(Long userId, int year, int month, String role) {
  if ("STAFF".equalsIgnoreCase(role)) {
    return netSalaryForStaffMonth(userId, year, month);
  }
  return netSalaryForUserMonth(userId, year, month); // your existing USER one
}

/**
 * Live preview of the payslip with unsaved overrides. Nothing is persisted:
 * the hourly cost used for the shift pay can be overridden and the saved
 * adjustments are replaced in-memory by the supplied ones. Used by the app
 * so tax and net salary update in real time while an admin edits.
 */
public NetSalaryResponse previewForUserMonth(Long userId, int year, int month, String role,
    BigDecimal hourlyCostOverride, List<SalaryAdjustmentService.AdjustmentInput> adjustmentOverrides) {
  var user = userService.getDetailedById(userId);
  var profile = userService.getProfileByUserId(userId);

  BigDecimal rateForCalc = profile.getHourlyCost();
  BigDecimal monthlySalary = null;
  if (isMonthly(profile)) {
    if (profile.getMonthlySalary() == null) {
      throw new IllegalArgumentException("Monthly salary missing for user " + userId);
    }
    // For MONTHLY employees the override carries the edited monthly salary.
    monthlySalary = profile.getMonthlySalary();
    if (hourlyCostOverride != null && hourlyCostOverride.signum() >= 0) {
      monthlySalary = hourlyCostOverride.setScale(2, RoundingMode.HALF_UP);
    }
    rateForCalc = monthlySalary;
  } else {
    if (profile.getHourlyCost() == null) {
      throw new IllegalArgumentException("Hourly cost missing for user " + userId);
    }
    if (hourlyCostOverride != null && hourlyCostOverride.signum() >= 0) {
      rateForCalc = hourlyCostOverride.setScale(2, RoundingMode.HALF_UP);
    }
  }
  BigDecimal hourlyCost = rateForCalc;

  int taxYear = year;
  int taxColumn = taxService.resolveTaxColumn(requireYearOfBirth(profile, userId), taxYear);
  int tableNumber = taxService.resolveTableNumber(profile.getMunicipalityCode(), taxYear);

  var range = monthRangeUTC(year, month);
  BigDecimal totalHours = BigDecimal.ZERO;
  PayBreakdown pay = PayBreakdown.zero();
  if ("STAFF".equalsIgnoreCase(role)) {
    for (var s : staffScheduleService.listForUser(userId, range.start(), range.end())) {
      Instant start = s.getStartAt().toInstant();
      Instant end = s.getEndAt().toInstant();
      if (!startsIn(range, start)) continue;
      totalHours = totalHours.add(hoursBetween(start, end));
      if (!isMonthly(profile)) pay = pay.add(shiftPay(start, end, hourlyCost));
    }
  } else {
    boolean overridden = hourlyCostOverride != null && hourlyCostOverride.signum() >= 0;
    for (var s : scheduleService.listForUser(userId, range.start(), range.end())) {
      Instant start = s.getStartAt().toInstant();
      Instant end = s.getEndAt().toInstant();
      if (!startsIn(range, start)) continue;
      totalHours = totalHours.add(hoursBetween(start, end));
      // An explicit preview override re-prices every shift on purpose.
      BigDecimal shiftRate = overridden ? hourlyCost : rateOf(s, hourlyCost);
      if (!isMonthly(profile)) pay = pay.add(shiftPay(start, end, shiftRate));
    }
  }
  // MONTHLY: gross is the fixed salary regardless of shift count.
  if (isMonthly(profile)) {
    pay = PayBreakdown.fixed(monthlySalary);
  }

  return calculate(userId, year, month, hourlyCost, profile.getPayType(), monthlySalary,
      totalHours, pay, taxColumn, tableNumber,
      profile.getMunicipalityCode(), adjustmentOverrides);
}

public NetSalaryResponse netSalaryForStaffMonth(Long userId, int year, int month) {
  // Same logic as netSalaryForUserMonth but ONLY use staffScheduleService shifts
  var user = userService.getDetailedById(userId);
  var profile = userService.getProfileByUserId(userId);

  BigDecimal rate = profile.getHourlyCost();
  if (isMonthly(profile)) {
    if (profile.getMonthlySalary() == null) {
      throw new IllegalArgumentException("Monthly salary missing for user " + userId);
    }
    rate = profile.getMonthlySalary();
  } else if (profile.getHourlyCost() == null) {
    throw new IllegalArgumentException("Hourly cost missing for user " + userId);
  }

  var range = monthRangeUTC(year, month);
  var shifts = staffScheduleService.listForUser(userId, range.start(), range.end());

  BigDecimal totalHours = BigDecimal.ZERO;
  PayBreakdown pay = PayBreakdown.zero();
  for (var s : shifts) {
    Instant start = s.getStartAt().toInstant();
    Instant end = s.getEndAt().toInstant();
    if (!startsIn(range, start)) continue;
    totalHours = totalHours.add(hoursBetween(start, end));
    if (!isMonthly(profile)) pay = pay.add(shiftPay(start, end, rate));
  }
  if (isMonthly(profile)) {
    pay = PayBreakdown.fixed(rate);
  }

  int taxYear = year;
  int taxColumn = taxService.resolveTaxColumn(requireYearOfBirth(profile, userId), taxYear);
  int tableNumber = taxService.resolveTableNumber(profile.getMunicipalityCode(), taxYear);

  return calculate(userId,year,month,rate,isMonthly(profile) ? "MONTHLY" : "HOURLY",isMonthly(profile) ? rate : null,totalHours,pay,taxColumn,tableNumber,profile.getMunicipalityCode());
}
  public NetSalaryResponse netSalaryForUserMonth(Long userId, int year, int month) {

    var user = userService.getDetailedById(userId);
    var profile = userService.getProfileByUserId(userId); // implement helper that returns UserProfile

    BigDecimal rate = profile.getHourlyCost();
    if (isMonthly(profile)) {
      if (profile.getMonthlySalary() == null) {
        throw new IllegalArgumentException("Monthly salary missing for user " + userId);
      }
      rate = profile.getMonthlySalary();
    } else if (profile.getHourlyCost() == null) {
      throw new IllegalArgumentException("Hourly cost missing for user " + userId);
    }

    var range = monthRangeUTC(year, month);
    var shifts = scheduleService.listForUser(userId, range.start(), range.end());

    BigDecimal totalHours = BigDecimal.ZERO;
    PayBreakdown pay = PayBreakdown.zero();
    for (var s : shifts) {
      Instant start = s.getStartAt().toInstant();
      Instant end = s.getEndAt().toInstant();
      if (!startsIn(range, start)) continue;
      totalHours = totalHours.add(hoursBetween(start, end));
      if (!isMonthly(profile)) pay = pay.add(shiftPay(start, end, rateOf(s, rate)));
    }
    // MONTHLY: gross is the fixed salary even with zero shifts.
    if (isMonthly(profile)) {
      pay = PayBreakdown.fixed(rate);
    }

    int taxYear = year;
    int taxColumn = taxService.resolveTaxColumn(requireYearOfBirth(profile, userId), taxYear);
    int tableNumber = taxService.resolveTableNumber(profile.getMunicipalityCode(), taxYear);

    return calculate(userId,year,month,rate,isMonthly(profile) ? "MONTHLY" : "HOURLY",isMonthly(profile) ? rate : null,totalHours,pay,taxColumn,tableNumber,profile.getMunicipalityCode());
  }

  private NetSalaryResponse calculate(Long userId,int year,int month,BigDecimal hourlyCost,String payType,BigDecimal monthlySalary,BigDecimal hours,PayBreakdown pay,int column,int table,String municipality){
    return calculate(userId,year,month,hourlyCost,payType,monthlySalary,hours,pay,column,table,municipality,null);
  }

  private NetSalaryResponse calculate(Long userId,int year,int month,BigDecimal hourlyCost,String payType,BigDecimal monthlySalary,BigDecimal hours,PayBreakdown pay,int column,int table,String municipality,List<SalaryAdjustmentService.AdjustmentInput> adjustmentOverrides){
    BigDecimal baseGross = pay.total();
    var items=adjustmentOverrides != null
        ? adjustmentService.toEntities(adjustmentOverrides)
        : adjustmentService.forMonth(userId,year,month);
    BigDecimal regular=items.stream().map(a -> {
      if(a.getTaxTreatment()==com.nordicframtiden.service.model.SalaryAdjustment.TaxTreatment.REGULAR_TAXABLE) return a.getAmount();
      if(a.getTaxTreatment()==com.nordicframtiden.service.model.SalaryAdjustment.TaxTreatment.TAX_FREE) return a.getAmount().subtract(adjustmentService.taxFreePortion(a));
      return BigDecimal.ZERO;
    }).reduce(BigDecimal.ZERO,BigDecimal::add);
    BigDecimal oneTime=items.stream().filter(a->a.getTaxTreatment()==com.nordicframtiden.service.model.SalaryAdjustment.TaxTreatment.ONE_TIME_TAXABLE).map(a->a.getAmount()).reduce(BigDecimal.ZERO,BigDecimal::add);
    BigDecimal taxFree=items.stream().map(adjustmentService::taxFreePortion).reduce(BigDecimal.ZERO,BigDecimal::add);
    BigDecimal monthlyTaxable=baseGross.add(regular);
    int regularTaxInt = monthlyTaxable.signum() == 0
        ? 0
        : taxService.lookupPreliminaryTax(
            year, table, column, monthlyTaxable.setScale(0, RoundingMode.HALF_UP).intValue());
    BigDecimal annualOneTime=adjustmentOverrides == null
        ? adjustmentService.annualOneTimeTotal(userId,year)
        : adjustmentService.annualOneTimeTotalExcludingMonth(userId,year,month).add(oneTime);
    BigDecimal projected=monthlyTaxable.multiply(BigDecimal.valueOf(12)).add(annualOneTime);
    int rate = oneTime.signum() == 0 ? 0
        : monthlyTaxable.signum() == 0 ? 30
        : oneTimeTaxService.rateFor(year, column,
            projected.setScale(0,RoundingMode.HALF_UP).intValue());
    // One-time tax follows the official tables' convention: whole kronor, öre discarded.
    BigDecimal oneTimeTax=oneTime.multiply(BigDecimal.valueOf(rate)).divide(BigDecimal.valueOf(100),0,RoundingMode.DOWN);
    BigDecimal tax=BigDecimal.valueOf(regularTaxInt).add(oneTimeTax);
    BigDecimal taxableGross=monthlyTaxable.add(oneTime);
    BigDecimal net=taxableGross.subtract(tax).add(taxFree);
    var lines=items.stream().map(a->new NetSalaryResponse.AdjustmentLine(a.getId(),a.getName(),a.getAmount(),a.getTaxTreatment(),a.getReimbursementType(),a.getQuantity(),a.getReceiptReference(),a.isTaxFreeEligibilityConfirmed(),adjustmentService.taxFreePortion(a))).toList();
    return new NetSalaryResponse(userId,String.format("%04d-%02d",year,month),hourlyCost,payType,monthlySalary == null ? null : monthlySalary.setScale(2,RoundingMode.HALF_UP),hours.setScale(2,RoundingMode.HALF_UP),taxableGross.setScale(2,RoundingMode.HALF_UP),year,municipality,table,column,tax.setScale(2,RoundingMode.HALF_UP),net.setScale(2,RoundingMode.HALF_UP),BigDecimal.valueOf(regularTaxInt).setScale(2),oneTimeTax.setScale(2),taxFree.setScale(2,RoundingMode.HALF_UP),projected.setScale(2,RoundingMode.HALF_UP),lines,pay.base().setScale(2,RoundingMode.HALF_UP),pay.saturdayOb().setScale(2,RoundingMode.HALF_UP),pay.sundayOb().setScale(2,RoundingMode.HALF_UP));
  }


  /**
   * The hourly rate frozen on the shift when it was booked (or reassigned),
   * so a later raise never re-prices work already done; the current profile
   * rate only for legacy shifts without a snapshot.
   */
  private static BigDecimal rateOf(com.nordicframtiden.pharmacy.ScheduleShift shift, BigDecimal profileRate) {
    return shift.getHourlyCostSnapshot() != null ? shift.getHourlyCostSnapshot() : profileRate;
  }

  /** The tax column depends on age; a missing birth year must not surface as an NPE (500). */
  private static int requireYearOfBirth(com.nordicframtiden.security.model.UserProfile profile, Long userId) {
    if (profile.getYearOfBirth() == null) {
      throw new IllegalArgumentException("Year of birth missing for user " + userId);
    }
    return profile.getYearOfBirth();
  }

  private boolean isMonthly(com.nordicframtiden.security.model.UserProfile profile) {
    return "MONTHLY".equalsIgnoreCase(profile.getPayType());
  }

  private record UtcRange(Instant start, Instant end) {}

  private UtcRange monthRangeUTC(int year, int month) {
    var start = LocalDate.of(year, month, 1).atStartOfDay(STOCKHOLM).toInstant();
    var end = LocalDate.of(year, month, 1).plusMonths(1).atStartOfDay(STOCKHOLM).toInstant();
    return new UtcRange(start, end);
  }

  /**
   * The range query returns every shift that overlaps the month, so a night
   * shift across a month boundary comes back for both months. A shift belongs
   * to the month it starts in (Stockholm time) — paid once, in full.
   */
  private boolean startsIn(UtcRange range, Instant start) {
    return start != null && !start.isBefore(range.start()) && start.isBefore(range.end());
  }

  private BigDecimal hoursBetween(Instant start, Instant end) {
    if (start == null || end == null) return BigDecimal.ZERO;
    long ms = Duration.between(start, end).toMillis();
    if (ms <= 0) return BigDecimal.ZERO;
    return BigDecimal.valueOf(ms).divide(BigDecimal.valueOf(3600000), 6, RoundingMode.HALF_UP);
  }

  private PayBreakdown shiftPay(Instant start, Instant end, BigDecimal hourlyCost) {
    if (start == null || end == null || !end.isAfter(start)) return PayBreakdown.zero();
    BigDecimal base = BigDecimal.ZERO, saturday = BigDecimal.ZERO, sunday = BigDecimal.ZERO;
    Instant cursor = start;
    while (cursor.isBefore(end)) {
      ZonedDateTime local = cursor.atZone(STOCKHOLM);
      Instant nextMidnight = local.toLocalDate().plusDays(1).atStartOfDay(STOCKHOLM).toInstant();
      Instant segmentEnd = end.isBefore(nextMidnight) ? end : nextMidnight;
      BigDecimal segmentPay = hourlyCost.multiply(hoursBetween(cursor, segmentEnd));
      base = base.add(segmentPay);
      if (local.getDayOfWeek() == DayOfWeek.SATURDAY) saturday = saturday.add(segmentPay.multiply(new BigDecimal("0.5")));
      if (local.getDayOfWeek() == DayOfWeek.SUNDAY) sunday = sunday.add(segmentPay);
      cursor = segmentEnd;
    }
    return new PayBreakdown(base, saturday, sunday);
  }

  private record PayBreakdown(BigDecimal base, BigDecimal saturdayOb, BigDecimal sundayOb) {
    static PayBreakdown zero(){return new PayBreakdown(BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO);}
    /** MONTHLY employees: the whole gross is the fixed salary (no OB parts). */
    static PayBreakdown fixed(BigDecimal salary){return new PayBreakdown(salary,BigDecimal.ZERO,BigDecimal.ZERO);}
    BigDecimal total(){return base.add(saturdayOb).add(sundayOb);}
    PayBreakdown add(PayBreakdown other){return new PayBreakdown(base.add(other.base),saturdayOb.add(other.saturdayOb),sundayOb.add(other.sundayOb));}
  }

  /** Request body for the live payslip preview; every field is an optional override. */
  public record PreviewRequest(BigDecimal hourlyCost, List<SalaryAdjustmentService.AdjustmentInput> adjustments) {}
}
