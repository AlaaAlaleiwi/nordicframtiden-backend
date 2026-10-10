package com.nordicframtiden.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nordicframtiden.security.repo.AppUserRepository;
import com.nordicframtiden.service.model.*;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;

/** Draft calculations are live. Finalization and subsequent corrections are append-only. */
@Service
public class PayslipFreezeService {
  private final PayslipSnapshotRepository snapshots;
  private final PayslipRevisionRepository revisions;
  private final PayrollService payroll;
  private final SalaryAdjustmentService adjustments;
  private final AppUserRepository users;
  private final ObjectMapper json;
  private final Clock clock;

  @Autowired
  public PayslipFreezeService(PayslipSnapshotRepository snapshots, PayslipRevisionRepository revisions,
      PayrollService payroll, SalaryAdjustmentService adjustments, AppUserRepository users, ObjectMapper json,
      Clock clock) {
    this.snapshots = snapshots;
    this.revisions = revisions;
    this.payroll = payroll;
    this.adjustments = adjustments;
    this.users = users;
    this.json = json;
    this.clock = clock;
  }

  PayslipFreezeService(PayslipSnapshotRepository snapshots, PayslipRevisionRepository revisions,
      PayrollService payroll, SalaryAdjustmentService adjustments, AppUserRepository users, ObjectMapper json) {
    this(snapshots, revisions, payroll, adjustments, users, json,
        Clock.system(ZoneId.of("Europe/Stockholm")));
  }

  public record Correction(Integer expectedRevision, String reason, BigDecimal regularGrossDelta,
      BigDecimal oneTimeGrossDelta, BigDecimal regularTaxDelta, BigDecimal oneTimeTaxDelta,
      BigDecimal taxFreeDelta) {}
  public record Revision(int revision, String actor, Instant createdAt, String reason,
      Correction changes, NetSalaryResponse payslip) {}

  @Transactional
  public NetSalaryResponse resolve(Long userId, int year, int month, String role) {
    var existing = snapshot(userId, year, month, role);
    if (existing.isPresent()) return read(latest(existing.get()).getPayload(), NetSalaryResponse.class);
    if (isClosedPeriod(year, month)) {
      return finalizeInternal(userId, year, month, role, "system-payroll-deadline").payslip();
    }
    return live(userId, year, month, role);
  }

  @Transactional
  public List<Revision> history(Long userId, int year, int month, String role) {
    if (snapshot(userId, year, month, role).isEmpty() && isClosedPeriod(year, month)) {
      finalizeInternal(userId, year, month, role, "system-payroll-deadline");
    }
    return snapshot(userId, year, month, role).map(s -> {
      var rows = revisions.findBySnapshotIdOrderByRevisionAsc(s.getId());
      if (rows.isEmpty()) throw new PayslipConflictException("Finalized payslip history is missing; restore the stored record");
      return rows.stream().map(this::view).toList();
    }).orElse(List.of());
  }

  @Transactional
  public Revision finalizePayslip(Long userId, int year, int month, String role, String actor) {
    requireFinalizationWindow(year, month);
    return finalizeInternal(userId, year, month, role, actor);
  }

  /**
   * Reopens an accidentally finalized payslip as a draft by removing the
   * snapshot and its revision history (revisions are also removed explicitly
   * so the operation never depends on cascade configuration). Guarded to the
   * same windows as editing — the current month, or the previous month
   * through the 20th — so closed payroll history can never be reopened here.
   * Fix tool for snapshots created before the finalization-window check
   * existed (e.g. a finalized current month, which silently locks every
   * add-field/delete/save action in the apps).
   */
  @Transactional
  public void unfinalize(Long userId, int year, int month, String role) {
    requireEditablePeriod(year, month);
    lock(userId, year, month, role);
    reopenWithinEditableWindow(userId, year, month, role);
  }

  /** Deletes the frozen snapshot + revisions; caller has verified the editable window. */
  private void reopenWithinEditableWindow(Long userId, int year, int month, String role) {
    var existing = snapshot(userId, year, month, role);
    if (existing.isEmpty()) return; // nothing to reopen — idempotent
    revisions.deleteAllBySnapshotId(existing.get().getId());
    snapshots.delete(existing.get());
  }

  private Revision finalizeInternal(Long userId, int year, int month, String role, String actor) {
    lock(userId, year, month, role);
    var existing = snapshot(userId, year, month, role);
    if (existing.isPresent()) return view(latest(existing.get())); // retry is idempotent
    NetSalaryResponse value = live(userId, year, month, role);
    String payload = write(value);
    PayslipSnapshot s = new PayslipSnapshot();
    s.setUserId(userId); s.setYear(year); s.setMonth(month); s.setRole(normalizeRole(role)); s.setPayload(payload);
    s = snapshots.saveAndFlush(s);
    return view(revisions.saveAndFlush(new PayslipRevision(s.getId(), 1, actor,
        "Finalized saved payroll calculation", null, payload)));
  }

  @Transactional
  public NetSalaryResponse saveAdjustments(Long userId, int year, int month, String role,
      List<SalaryAdjustmentService.AdjustmentInput> inputs) {
    requireEditablePeriod(year, month);
    lock(userId, year, month, role);
    // The editable window beats finalization: a month finalized inside the
    // window (current month; previous month through the 20th) is reopened as
    // a draft first, so fields stay addable/deletable/savable. Once the
    // window closes, requireEditablePeriod refuses and the freeze stands.
    // Adjustments currently belong to a user/month, shared by USER and STAFF.
    // Corrections are deliberate audit records: never erase them as a side
    // effect of saving — that takes an explicit unfinalize.
    requireNoCorrections(userId, year, month, "USER");
    requireNoCorrections(userId, year, month, "STAFF");
    reopenWithinEditableWindow(userId, year, month, "USER");
    reopenWithinEditableWindow(userId, year, month, "STAFF");
    requireDraft(userId, year, month, "USER");
    requireDraft(userId, year, month, "STAFF");
    adjustments.replace(userId, year, month, inputs == null ? List.of() : inputs);
    return live(userId, year, month, role);
  }

  @Transactional
  public NetSalaryResponse preview(Long userId, int year, int month, String role, PayrollService.PreviewRequest request) {
    requireEditablePeriod(year, month);
    lock(userId, year, month, role);
    // A frozen-but-editable month may be previewed (the UI computes live tax
    // with unsaved adjustments); after the window closes this is refused.
    return payroll.previewForUserMonth(userId, year, month, role, request.hourlyCost(), request.adjustments());
  }

  @Transactional
  public Revision correct(Long userId, int year, int month, String role, Correction change, String actor) {
    requireEditablePeriod(year, month);
    lock(userId, year, month, role);
    PayslipSnapshot s = snapshot(userId, year, month, role)
        .orElseThrow(() -> new PayslipConflictException("Finalize the payslip before creating a correction"));
    PayslipRevision previous = latest(s);
    if (change == null || change.expectedRevision() == null || change.expectedRevision() != previous.getRevision())
      throw new PayslipConflictException("Payslip revision changed; reload and review before correcting");
    if (change.reason() == null || change.reason().isBlank() || change.reason().length() > 1000)
      throw new IllegalArgumentException("A correction reason (1–1000 characters) is required");
    List<BigDecimal> deltas = java.util.Arrays.asList(change.regularGrossDelta(), change.oneTimeGrossDelta(),
        change.regularTaxDelta(), change.oneTimeTaxDelta(), change.taxFreeDelta());
    for (BigDecimal delta : deltas) {
      if (delta == null || delta.stripTrailingZeros().scale() > 2 || delta.abs().compareTo(new BigDecimal("10000000")) > 0)
        throw new IllegalArgumentException("Enter all five signed SEK changes, with at most two decimal places");
    }
    if (deltas.stream().allMatch(d -> d.signum() == 0)) throw new IllegalArgumentException("Correction has no changes");
    NetSalaryResponse old = read(previous.getPayload(), NetSalaryResponse.class);
    if (old.grossSalary() == null || old.preliminaryTax() == null || old.netSalary() == null ||
        old.grossSalary().subtract(old.preliminaryTax()).add(orZero(old.taxFreeAmount())).compareTo(old.netSalary()) != 0)
      throw new PayslipConflictException("Stored payslip totals do not reconcile; review the legacy record before correcting");
    BigDecimal originalRegularTax = old.regularTax() == null ? old.preliminaryTax().subtract(orZero(old.oneTimeTax())) : old.regularTax();
    if (originalRegularTax.add(orZero(old.oneTimeTax())).compareTo(old.preliminaryTax()) != 0)
      throw new PayslipConflictException("Stored withholding breakdown does not reconcile; review the legacy record before correcting");
    BigDecimal originalOneTime = (old.adjustments() == null ? List.<NetSalaryResponse.AdjustmentLine>of() : old.adjustments())
        .stream().filter(a -> a.taxTreatment() == SalaryAdjustment.TaxTreatment.ONE_TIME_TAXABLE)
        .map(NetSalaryResponse.AdjustmentLine::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
    if (originalOneTime.add(change.oneTimeGrossDelta()).signum() < 0 ||
        old.grossSalary().subtract(originalOneTime).add(change.regularGrossDelta()).signum() < 0)
      throw new IllegalArgumentException("Correction would make a taxable pay category negative");
    BigDecimal gross = old.grossSalary().add(change.regularGrossDelta()).add(change.oneTimeGrossDelta());
    BigDecimal regularTax = originalRegularTax.add(change.regularTaxDelta());
    BigDecimal oneTimeTax = orZero(old.oneTimeTax()).add(change.oneTimeTaxDelta());
    BigDecimal tax = old.preliminaryTax().add(change.regularTaxDelta()).add(change.oneTimeTaxDelta());
    BigDecimal taxFree = orZero(old.taxFreeAmount()).add(change.taxFreeDelta());
    BigDecimal net = gross.subtract(tax).add(taxFree);
    if (List.of(gross, regularTax, oneTimeTax, tax, taxFree, net).stream().anyMatch(v -> v.signum() < 0))
      throw new IllegalArgumentException("Correction would make a payslip total negative; use the payroll recovery process");
    var lines = new ArrayList<NetSalaryResponse.AdjustmentLine>(old.adjustments() == null ? List.of() : old.adjustments());
    String label = "Correction r" + (previous.getRevision() + 1) + ": " + change.reason().trim();
    addLine(lines, label, change.regularGrossDelta(), SalaryAdjustment.TaxTreatment.REGULAR_TAXABLE);
    addLine(lines, label, change.oneTimeGrossDelta(), SalaryAdjustment.TaxTreatment.ONE_TIME_TAXABLE);
    addLine(lines, label, change.taxFreeDelta(), SalaryAdjustment.TaxTreatment.TAX_FREE);
    NetSalaryResponse corrected = new NetSalaryResponse(old.userId(), old.monthKey(), old.hourlyCost(),
        old.payType(), old.monthlySalary(), old.totalHours(),
        gross, old.taxYear(), old.municipalityCode(), old.tableNumber(), old.taxColumn(), tax, net,
        regularTax, oneTimeTax, taxFree, old.projectedAnnualIncome(), lines, old.baseHourlySalary(), old.saturdayOb(), old.sundayOb());
    return view(revisions.saveAndFlush(new PayslipRevision(s.getId(), previous.getRevision() + 1, actor,
        change.reason().trim(), write(change), write(corrected))));
  }

  private static void addLine(List<NetSalaryResponse.AdjustmentLine> lines, String label, BigDecimal amount,
      SalaryAdjustment.TaxTreatment treatment) {
    if (amount.signum() != 0) lines.add(new NetSalaryResponse.AdjustmentLine(null, label, amount, treatment,
        null, null, null, treatment == SalaryAdjustment.TaxTreatment.TAX_FREE,
        treatment == SalaryAdjustment.TaxTreatment.TAX_FREE ? amount : BigDecimal.ZERO));
  }
  private static BigDecimal orZero(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }
  private Revision view(PayslipRevision r) {
    return new Revision(r.getRevision(), r.getActor(), r.getCreatedAt(), r.getReason(),
        r.getChanges() == null ? null : read(r.getChanges(), Correction.class), read(r.getPayload(), NetSalaryResponse.class));
  }
  private PayslipRevision latest(PayslipSnapshot s) {
    return revisions.findTopBySnapshotIdOrderByRevisionDesc(s.getId())
        .orElseThrow(() -> new PayslipConflictException("Finalized payslip history is missing; restore the stored record"));
  }
  private void requireNoCorrections(Long userId, int year, int month, String role) {
    var existing = snapshot(userId, year, month, role);
    if (existing.isPresent() && latest(existing.get()).getRevision() > 1)
      throw new PayslipConflictException(
          "Finalized payslip has correction revisions; create another correction or unfinalize it explicitly");
  }
  private void requireDraft(Long userId, int year, int month, String role) {
    if (snapshot(userId, year, month, role).isPresent())
      throw new PayslipConflictException("Finalized payslip is read-only; create a correction revision");
  }
  private Optional<PayslipSnapshot> snapshot(Long userId, int year, int month, String role) {
    YearMonth.of(year, month);
    return snapshots.findByUserIdAndYearAndMonthAndRole(userId, year, month, normalizeRole(role));
  }
  private void requireEditablePeriod(int year, int month) {
    YearMonth target = YearMonth.of(year, month);
    LocalDate today = LocalDate.now(clock);
    YearMonth current = YearMonth.from(today);
    boolean currentMonth = target.equals(current);
    boolean previousBeforeDeadline = target.equals(current.minusMonths(1)) && today.getDayOfMonth() <= 20;
    if (!currentMonth && !previousBeforeDeadline) {
      throw new PayslipConflictException(
          "Payroll period is closed. The previous month can be updated through the 20th of the payment month");
    }
  }
  private void requireFinalizationWindow(int year, int month) {
    LocalDate today = LocalDate.now(clock);
    YearMonth target = YearMonth.of(year, month);
    if (!target.equals(YearMonth.from(today).minusMonths(1)) || today.getDayOfMonth() > 20) {
      throw new PayslipConflictException(
          "Only the previous month's payslip can be finalized, through the 20th of the payment month");
    }
  }
  private boolean isClosedPeriod(int year, int month) {
    LocalDate today = LocalDate.now(clock);
    YearMonth target = YearMonth.of(year, month);
    YearMonth current = YearMonth.from(today);
    return target.isBefore(current.minusMonths(1))
        || (target.equals(current.minusMonths(1)) && today.getDayOfMonth() > 20);
  }
  private void lock(Long userId, int year, int month, String role) {
    YearMonth.of(year, month); normalizeRole(role);
    if (users.lockForPayroll(userId).isEmpty()) throw new IllegalArgumentException("User not found");
  }
  private NetSalaryResponse live(Long userId, int year, int month, String role) {
    return "STAFF".equals(normalizeRole(role)) ? payroll.netSalaryForStaffMonth(userId, year, month)
        : payroll.netSalaryForUserMonth(userId, year, month);
  }
  private <T> T read(String payload, Class<T> type) {
    try { return java.util.Objects.requireNonNull(json.readValue(payload, type)); }
    catch (Exception e) { throw new PayslipConflictException("Stored payslip cannot be read; restore it instead of recalculating history"); }
  }
  private String write(Object value) {
    try { return json.writeValueAsString(value); }
    catch (Exception e) { throw new IllegalStateException("Could not persist payslip", e); }
  }
  private static String normalizeRole(String role) {
    if ("STAFF".equalsIgnoreCase(role)) return "STAFF";
    if ("USER".equalsIgnoreCase(role)) return "USER";
    throw new IllegalArgumentException("Payroll role must be USER or STAFF");
  }
}
