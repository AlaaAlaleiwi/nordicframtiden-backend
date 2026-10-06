package com.nordicframtiden.api;

import com.nordicframtiden.company.StaffShift;
import com.nordicframtiden.company.StaffShiftRepository;
import com.nordicframtiden.pharmacy.ScheduleShift;
import com.nordicframtiden.pharmacy.ScheduleShiftRepository;
import com.nordicframtiden.security.model.Role;
import com.nordicframtiden.security.model.UserProfile;
import com.nordicframtiden.security.repo.AppUserRepository;
import com.nordicframtiden.security.repo.UserProfileRepository;
import com.nordicframtiden.service.PayrollService;
import com.nordicframtiden.service.PayslipDeliveryService;
import com.nordicframtiden.service.PayslipFreezeService;
import com.nordicframtiden.service.SalaryAdjustmentService;
import com.nordicframtiden.service.model.NetSalaryResponse;
import com.nordicframtiden.settings.EmailService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/salaries")
public class SalariesController {

  private static final ZoneId STOCKHOLM = ZoneId.of("Europe/Stockholm");
  private static final String CAN_MANAGE_SALARIES =
      "hasRole('ADMIN') or hasAuthority('PERM_SALARIES')";

  private final ScheduleShiftRepository shiftRepo;
  private final StaffShiftRepository staffShiftRepo;
  private final UserProfileRepository profileRepo;
  private final AppUserRepository userRepo;
  private final PayrollService payrollService;
  private final PayslipFreezeService payslipFreezeService;
  private final EmailService emailService;
  private final SalaryAdjustmentService adjustmentService;
  private final PayslipDeliveryService payslipDeliveryService;

  public SalariesController(
      ScheduleShiftRepository shiftRepo,
      StaffShiftRepository staffShiftRepo,
      UserProfileRepository profileRepo,
      AppUserRepository userRepo,
      PayrollService payrollService,
      PayslipFreezeService payslipFreezeService,
      EmailService emailService, SalaryAdjustmentService adjustmentService,
      PayslipDeliveryService payslipDeliveryService
  ) {
    this.shiftRepo = shiftRepo;
    this.staffShiftRepo = staffShiftRepo;
    this.profileRepo = profileRepo;
    this.userRepo = userRepo;
    this.payrollService = payrollService;
    this.payslipFreezeService = payslipFreezeService;
    this.emailService = emailService;
    this.adjustmentService = adjustmentService;
    this.payslipDeliveryService = payslipDeliveryService;
  }

  /* ===================== DTOs ===================== */

  public record ShiftLine(
      Long shiftId,
      Long pharmacyId,
      String pharmacyName,
      Long userId,
      String userFullName,
      OffsetDateTime startAt,
      OffsetDateTime endAt,
      double hours,
      BigDecimal hourlyCost,
      BigDecimal cost
  ) {}

  public record UserSummary(
      Long userId,
      String fullName,
      double hours,
      BigDecimal hourlyCost,
      String payType,
      BigDecimal monthlySalary,
      BigDecimal totalCost
  ) {}

  public record PharmacySummary(
      Long pharmacyId,
      String pharmacyName,
      double totalHours,
      BigDecimal totalCost,
      List<UserSummary> users
  ) {}

  public record YearRow(int year) {}
  public record AdjustmentRequest(List<SalaryAdjustmentService.AdjustmentInput> adjustments) {}

  @PutMapping("/payslip/adjustments")
  @PreAuthorize(CAN_MANAGE_SALARIES)
  public NetSalaryResponse saveAdjustments(@RequestParam Long userId,@RequestParam int year,@RequestParam int month,@RequestParam(defaultValue="USER") String role,@RequestBody AdjustmentRequest request){
    return payslipFreezeService.saveAdjustments(userId, year, month, role, request.adjustments());
  }
  /** Live preview: computes the payslip with unsaved hourly cost / adjustment overrides. Nothing is persisted. */
  @PostMapping("/payslip/preview")
  @PreAuthorize(CAN_MANAGE_SALARIES)
  public NetSalaryResponse previewPayslip(
      @RequestParam Long userId,
      @RequestParam int year,
      @RequestParam int month,
      @RequestParam(defaultValue = "USER") String role,
      @RequestBody(required = false) PayrollService.PreviewRequest request
  ) {
    PayrollService.PreviewRequest body =
        request == null ? new PayrollService.PreviewRequest(null, null) : request;
    return payslipFreezeService.preview(userId, year, month, role, body);
  }
  @GetMapping("/payslip/revisions")
  @PreAuthorize(CAN_MANAGE_SALARIES)
  public List<PayslipFreezeService.Revision> revisions(@RequestParam Long userId, @RequestParam int year,
      @RequestParam int month, @RequestParam(defaultValue = "USER") String role) {
    return payslipFreezeService.history(userId, year, month, role);
  }

  @PostMapping("/payslip/finalize")
  @PreAuthorize(CAN_MANAGE_SALARIES)
  public PayslipFreezeService.Revision finalizePayslip(@RequestParam Long userId, @RequestParam int year,
      @RequestParam int month, @RequestParam(defaultValue = "USER") String role, Authentication auth) {
    return payslipFreezeService.finalizePayslip(userId, year, month, role, auth.getName());
  }

  /**
   * Reopen an accidentally finalized payslip as a draft (removes the snapshot
   * + revisions). Allowed only for the current month, or the previous month
   * through the 20th — the same windows as editing.
   */
  @DeleteMapping("/payslip/finalize")
  @PreAuthorize(CAN_MANAGE_SALARIES)
  public ResponseEntity<Void> unfinalizePayslip(@RequestParam Long userId,
      @RequestParam int year, @RequestParam int month, @RequestParam(defaultValue = "USER") String role) {
    payslipFreezeService.unfinalize(userId, year, month, role);
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/payslip/corrections")
  @PreAuthorize(CAN_MANAGE_SALARIES)
  public PayslipFreezeService.Revision correctPayslip(@RequestParam Long userId, @RequestParam int year,
      @RequestParam int month, @RequestParam(defaultValue = "USER") String role,
      @RequestBody PayslipFreezeService.Correction correction, Authentication auth) {
    return payslipFreezeService.correct(userId, year, month, role, correction, auth.getName());
  }

  public record MonthRow(int year, int month, double totalHours, BigDecimal totalCost) {}
  public record DayRow(String dayKey, OffsetDateTime from, OffsetDateTime to, double totalHours, BigDecimal totalCost) {}
// ===== Payslip DTO (what frontend expects) =====


 
// ===== /api/salaries/payslip/me (USER/STAFF/ADMIN) =====
 
// ===== /api/salaries/payslip?userId= (ADMIN only) =====
@GetMapping("/payslip")
  @PreAuthorize(CAN_MANAGE_SALARIES)
  public NetSalaryResponse payslipForUser(
      @RequestParam Long userId,
      @RequestParam int year,
      @RequestParam int month
  ) {
    // Finalized months resolve to their latest immutable revision.
    return payslipFreezeService.resolve(userId, year, month, "USER");
  }
  /* ===================== PAYSLIP (ME) ===================== */
@GetMapping("/payslip/staff")
@PreAuthorize(CAN_MANAGE_SALARIES)
public NetSalaryResponse payslipForStaff(
    @RequestParam Long userId,
    @RequestParam int year,
    @RequestParam int month
) {
  return payslipFreezeService.resolve(userId, year, month, "STAFF");
}
  // GET /api/salaries/payslip/me?year=2026&month=3
  @GetMapping("/payslip/me")
  @PreAuthorize("isAuthenticated()")
  public NetSalaryResponse payslipMe(
      @RequestParam int year,
      @RequestParam int month,
      Authentication auth
  ) {
    return payslipFreezeService.resolve(currentUserId(auth), year, month, "USER");
  }

  /**
   * Self-service readiness info for the salary screens: when the previous
   * work month's payslip was automatically emailed/announced (the 21st or
   * previous working day), and which work month was delivered last.
 */
  @GetMapping("/payslip/ready-status")
  @PreAuthorize("isAuthenticated()")
  public Map<String, Object> payslipReadyStatus(Authentication auth) {
    java.time.LocalDate today = java.time.LocalDate.now(ZoneId.of("Europe/Stockholm"));
    java.time.YearMonth payoutMonth = java.time.YearMonth.from(today);
    java.time.YearMonth workMonth = payoutMonth.minusMonths(1);
    java.time.LocalDate readyDate = payslipDeliveryService.readyDateFor(payoutMonth);

    boolean ready = !today.isBefore(readyDate);
    var delivered = payslipDeliveryService.lastDelivered(currentUserId(auth), "USER");

    // Map.of rejects null values. Before the first successful delivery there
    // is no lastDeliveredMonth, so use a mutable map to return JSON null rather
    // than turning this informational endpoint into a 500 response.
    Map<String, Object> status = new LinkedHashMap<>();
    status.put("ready", ready);
    status.put("readyDate", readyDate.toString());
    status.put("workMonth", workMonth.toString());
    status.put("lastDeliveredMonth", delivered.map(java.time.YearMonth::toString).orElse(null));
    return status;
  }

  /* ===================== Admin audit + resend ===================== */

  /** Audit listing: every auto-delivery row for one work month. */
  @GetMapping("/payslip/deliveries")
  @PreAuthorize(CAN_MANAGE_SALARIES)
  public List<PayslipDeliveryService.DeliveryRow> payslipDeliveries(
      @RequestParam int year, @RequestParam int month) {
    return payslipDeliveryService.deliveriesForMonth(year, month);
  }

  /** Re-queues one finished/failed delivery and sends it immediately. */
  @PostMapping("/payslip/deliveries/{id}/resend")
  @PreAuthorize(CAN_MANAGE_SALARIES)
  public Map<String, Object> resendPayslipDelivery(@PathVariable Long id) {
    PayslipDeliveryService.ResendResult result = payslipDeliveryService.resendAndDeliver(id);
    if (!result.queued()) {
      return Map.of("queued", false, "sent", false, "reason",
          "Already queued or currently being delivered");
    }
    return Map.of("queued", true, "sent", result.sent());
  }

  /** Queues rows for every eligible account missing one for the month. */
  @PostMapping("/payslip/deliveries/queue-missing")
  @PreAuthorize(CAN_MANAGE_SALARIES)
  public Map<String, Object> queueMissingPayslipDeliveries(
      @RequestParam int year, @RequestParam int month) {
    YearMonth workMonth = YearMonth.of(year, month); // validates the month range
    int queued = payslipDeliveryService.queueMissing(workMonth);
    // Queueing missing rows does not synchronously drain delivery work. The
    // scheduled worker sends them later without touching unrelated months.
    return Map.of("queued", queued, "sent", false);
  }

  /* ===================== MONTH SUMMARY ===================== */

  @GetMapping("/month")
  @PreAuthorize(CAN_MANAGE_SALARIES)
  public List<PharmacySummary> monthly(
      @RequestParam OffsetDateTime start,
      @RequestParam OffsetDateTime end,
      @RequestParam(defaultValue = "USER") String role
  ) {
    List<ShiftLine> lines;
    Map<Long, UserProfileRepository.UserProfileSummary> profilesByUserId;
    if ("STAFF".equalsIgnoreCase(role)) {
      var shifts = staffShiftRepo.findInRange(start, end, null);
      profilesByUserId = profilesForUsers(shifts.stream().map(shift -> shift.getUser().getId()).toList());
      lines = shifts.stream()
          .map(shift -> toStaffLine(shift, profilesByUserId.get(shift.getUser().getId())))
          .toList();
    } else {
      var shifts = shiftRepo.findInRange(start, end, null, null);
      profilesByUserId = profilesForUsers(shifts.stream().map(shift -> shift.getUser().getId()).toList());
      lines = shifts.stream()
          .map(shift -> toUserLine(shift, profilesByUserId.get(shift.getUser().getId())))
          .toList();
    }

    List<PharmacySummary> summaries = summarizeByPharmacy(lines, profilesByUserId);

    // Payslip adjustment fields (bonuses, one-time pay, tax-free
    // reimbursements) are part of the month's employer cost but live outside
    // the shifts. Fold the work month's adjustment totals into the matching
    // person so the salary lists agree with the payslip gross on all
    // platforms. Tax-free adjustments count toward the cost too — they are
    // pay, only the tax treatment differs.
    if (adjustmentService != null) {
      summaries = withPayslipAdjustments(summaries, lines, start, end, role);
    }
    // MONTHLY employees: the payslip gross is the fixed salary, not
    // hours × rate, so their salary entries must not depend on shifts.
    // Upsert their fixed-salary rows (hours stay informational).
    summaries = withMonthlySalaries(summaries, role);
    return summaries;
  }

  /** Pay type of a user's profile: "MONTHLY" or "HOURLY" (default). */
  private String payTypeOf(Long userId) {
    return profileRepo.findByUserId(userId)
        .map(p -> p.getPayType() == null || p.getPayType().isBlank() ? "HOURLY" : p.getPayType())
        .orElse("HOURLY");
  }

  /** True when the account holds the given role. */
  private boolean hasRole(Long userId, com.nordicframtiden.security.model.Role role) {
    return userRepo.findById(userId)
        .map(u -> u.getRoles() != null && u.getRoles().contains(role))
        .orElse(false);
  }

  /**
   * Upserts every MONTHLY employee of the role into the summaries with their
   * fixed salary as cost (skipping those already present — their cost is
   * corrected to the salary). Keeps salary lists matching the payslips even
   * in months with no shifts.
   */
  private List<PharmacySummary> withMonthlySalaries(List<PharmacySummary> summaries, String role) {
    boolean staff = "STAFF".equalsIgnoreCase(role);
    List<UserSummary> monthlyRows = new ArrayList<>();
    for (var profile : profileRepo.findMonthlySalaryProfilesByRole(staff ? Role.STAFF : Role.USER)) {
      Long userId = profile.getUserId();
      String name = (profile.getFullName() != null && !profile.getFullName().isBlank())
          ? profile.getFullName() : "user-" + userId;
      monthlyRows.add(new UserSummary(userId, name, 0, null, "MONTHLY", profile.getMonthlySalary(),
          profile.getMonthlySalary()));
    }
    if (monthlyRows.isEmpty()) return summaries;

    Map<Long, UserSummary> byId = monthlyRows.stream()
        .collect(Collectors.toMap(UserSummary::userId, u -> u));
    Set<Long> present = new HashSet<>();
    List<PharmacySummary> updated = new ArrayList<>();
    for (PharmacySummary pharmacy : summaries) {
      List<UserSummary> users = new ArrayList<>();
      double totalHours = 0;
      BigDecimal totalCost = BigDecimal.ZERO;
      for (UserSummary user : pharmacy.users()) {
        UserSummary monthly = byId.get(user.userId());
        if (monthly != null) {
          // MONTHLY: fixed salary replaces the shift-derived cost; hours stay.
          UserSummary corrected = new UserSummary(user.userId(), user.fullName(), user.hours(),
              null, "MONTHLY", monthly.monthlySalary(), monthly.totalCost());
          users.add(corrected);
          present.add(user.userId());
        } else {
          users.add(user);
        }
      }
      totalHours = users.stream().mapToDouble(UserSummary::hours).sum();
      totalCost = users.stream().map(UserSummary::totalCost).reduce(BigDecimal.ZERO, BigDecimal::add);
      updated.add(new PharmacySummary(
          pharmacy.pharmacyId(), pharmacy.pharmacyName(), totalHours, totalCost, users));
    }
    // MONTHLY employees with no shifts at all go into a synthetic group so
    // they still appear in the salary lists.
    List<UserSummary> missing = monthlyRows.stream()
        .filter(u -> !present.contains(u.userId())).toList();
    if (!missing.isEmpty()) {
      List<UserSummary> users = new ArrayList<>(missing);
      BigDecimal totalCost = users.stream().map(UserSummary::totalCost).reduce(BigDecimal.ZERO, BigDecimal::add);
      PharmacySummary synthetic = new PharmacySummary(0L, "Monthly salaries", 0, totalCost, users);
      updated.add(synthetic);
    }
    updated.sort((a, b) -> b.totalCost().compareTo(a.totalCost()));
    return updated;
  }

  /**
   * Adds each person's payslip adjustment total for the work month(s) covered
   * by [start, end) to their user summary (and the pharmacy totals).
   */
  private List<PharmacySummary> withPayslipAdjustments(
      List<PharmacySummary> summaries, List<ShiftLine> lines,
      OffsetDateTime start, OffsetDateTime end, String role) {
    // Work months covered by the requested window (the end is exclusive —
    // clients request exactly one calendar month; multiple are handled anyway).
    java.util.Set<java.time.YearMonth> months = new java.util.HashSet<>();
    java.time.OffsetDateTime cursor = start;
    while (cursor.isBefore(end) && months.size() < 24) {
      months.add(java.time.YearMonth.from(cursor));
      cursor = cursor.plusMonths(1);
    }
    if (months.isEmpty()) return summaries;

    // userId -> adjustment total for the covered work months. Adjustments
    // are shared per user + work month; role filtering below keeps them out
    // of the other role's view.
    Map<Long, BigDecimal> adjustmentTotals = new java.util.HashMap<>();
    for (java.time.YearMonth ym : months) {
      for (var adjustment : adjustmentService.forMonth(ym.getYear(), ym.getMonthValue())) {
        BigDecimal amount = adjustment.getAmount() == null ? BigDecimal.ZERO : adjustment.getAmount();
        adjustmentTotals.merge(adjustment.getUserId(), amount, BigDecimal::add);
      }
    }
    if (adjustmentTotals.isEmpty()) return summaries;

    // Include adjustment-only employees too so salary lists reconcile to all
    // saved payslip gross amounts, including months with no worked shifts.
    java.util.Set<Long> peopleInRole = lines.stream()
        .map(ShiftLine::userId).collect(java.util.stream.Collectors.toSet());
    List<PharmacySummary> sourceSummaries = new ArrayList<>(summaries);
    if (adjustmentTotals.keySet().stream().anyMatch(id -> !peopleInRole.contains(id)
        && isUserInSalaryRole(id, role))
        && sourceSummaries.stream().noneMatch(p -> p.pharmacyId() == 0L)) {
      sourceSummaries.add(new PharmacySummary(0L, "Adjustments", 0, BigDecimal.ZERO, List.of()));
    }

    Set<Long> adjustmentsApplied = new HashSet<>();
    List<PharmacySummary> updated = new ArrayList<>(sourceSummaries.size());
    for (PharmacySummary pharmacy : sourceSummaries) {
      List<UserSummary> users = new ArrayList<>(pharmacy.users().size());
      double totalHours = 0;
      BigDecimal totalCost = BigDecimal.ZERO;
      for (UserSummary user : pharmacy.users()) {
        BigDecimal extra = adjustmentsApplied.add(user.userId())
            ? adjustmentTotals.getOrDefault(user.userId(), BigDecimal.ZERO)
            : BigDecimal.ZERO;
        double hours = user.hours();
        BigDecimal cost = user.totalCost().add(extra);
        BigDecimal hourly = hours > 0
            ? cost.divide(BigDecimal.valueOf(hours), 2, RoundingMode.HALF_UP)
            : user.hourlyCost();
        users.add(new UserSummary(user.userId(), user.fullName(), hours, hourly,
            user.payType(), user.monthlySalary(), cost));
        totalHours += hours;
        totalCost = totalCost.add(cost);
      }
      if (pharmacy.pharmacyId() == 0L) {
        for (Long userId : adjustmentTotals.keySet()) {
          if (peopleInRole.contains(userId) || !isUserInSalaryRole(userId, role)) continue;
          BigDecimal cost = adjustmentTotals.getOrDefault(userId, BigDecimal.ZERO);
          users.add(new UserSummary(userId, salaryEmployeeName(userId), 0, BigDecimal.ZERO,
              payTypeOf(userId), null, cost));
          totalCost = totalCost.add(cost);
        }
      }

      users.sort((a, b) -> b.totalCost().compareTo(a.totalCost()));
      updated.add(new PharmacySummary(
          pharmacy.pharmacyId(), pharmacy.pharmacyName(), totalHours, totalCost, users));
    }
    updated.sort((a, b) -> b.totalCost().compareTo(a.totalCost()));
    return updated;
  }

  private boolean isUserInSalaryRole(Long userId, String role) {
    com.nordicframtiden.security.model.Role requiredRole = "STAFF".equalsIgnoreCase(role)
        ? com.nordicframtiden.security.model.Role.STAFF
        : com.nordicframtiden.security.model.Role.USER;
    return userRepo.findById(userId)
        .map(user -> user.getRoles() != null && user.getRoles().contains(requiredRole))
        .orElse(false);
  }

  private String salaryEmployeeName(Long userId) {
    return profileRepo.findByUserId(userId)
        .map(profile -> profile.getFullName() == null || profile.getFullName().isBlank()
            ? "#" + userId : profile.getFullName())
        .orElse("#" + userId);
  }

  /* ===================== REPORT ===================== */

  @GetMapping("/report")
  @PreAuthorize(CAN_MANAGE_SALARIES)
  public List<ShiftLine> report(
      @RequestParam OffsetDateTime start,
      @RequestParam OffsetDateTime end,
      @RequestParam(required = false) Long pharmacyId,
      @RequestParam(required = false) Long userId,
      @RequestParam(defaultValue = "USER") String role
  ) {
    if ("STAFF".equalsIgnoreCase(role)) {
      // staff has no pharmacy -> ignore pharmacyId
      return staffShiftRepo.findInRange(start, end, userId)
          .stream().map(this::toStaffLine).toList();
    }

    if (pharmacyId == null)
      throw new IllegalArgumentException("pharmacyId is required for USER report");

    return shiftRepo.findInRange(start, end, pharmacyId, userId)
        .stream().map(this::toUserLine).toList();
  }

  @PostMapping("/send-pdf-email")
  @PreAuthorize(CAN_MANAGE_SALARIES)
  public ResponseEntity<Map<String, Object>> sendSalaryPdfEmail(
      @RequestBody SalaryEmailRequest request
  ) {
    Long userId = request.userId();
    Integer year = request.year();
    Integer month = request.month();
    String role = request.role() == null ? "USER" : request.role();
    String pdfBase64 = request.pdfBase64() == null ? "" : request.pdfBase64().trim();

    if (userId == null || year == null || month == null || pdfBase64.isBlank()) {
      return ResponseEntity.status(HttpStatus.BAD_REQUEST)
          .body(Map.of("error", "Missing required fields to send the salary PDF email."));
    }

    var profile = profileRepo.findByUserId(userId).orElse(null);
    String email = profile == null || profile.getEmail() == null ? "" : profile.getEmail().trim();
    String employeeName = profile == null || profile.getFullName() == null ? "" : profile.getFullName().trim();
    if (email.isBlank() || !email.contains("@")) {
      return ResponseEntity.status(HttpStatus.BAD_REQUEST)
          .body(Map.of("error", "The employee does not have a valid profile email."));
    }

    byte[] pdfBytes;
    try {
      pdfBytes = Base64.getDecoder().decode(pdfBase64);
    } catch (IllegalArgumentException e) {
      return ResponseEntity.status(HttpStatus.BAD_REQUEST)
          .body(Map.of("error", "Invalid PDF payload."));
    }

    var payslip = payslipFreezeService.resolve(userId, year, month, role);
    if (payslip.grossSalary() == null || payslip.grossSalary().compareTo(BigDecimal.ZERO) == 0) {
      return ResponseEntity.ok(Map.of(
          "sent", false,
          "reason", "ZERO_SALARY",
          "recipient", email,
          "employeeName", employeeName,
          "month", String.format("%04d-%02d", year, month),
          "payslip", payslip
      ));
    }

    String monthLabel = String.format("%04d-%02d", year, month);
    boolean sent = emailService.sendSalaryPdfEmail(email, employeeName.isBlank() ? "Employee" : employeeName, pdfBytes, monthLabel);

    return ResponseEntity.ok(Map.of(
        "sent", sent,
        "recipient", email,
        "employeeName", employeeName,
        "month", monthLabel,
        "filename", "salary-" + monthLabel + ".pdf",
        "payslip", payslip
    ));
  }

  public record SalaryEmailRequest(
      Long userId,
      Integer year,
      Integer month,
      String role,
      String pdfBase64
  ) {}

  /* ===================== LAZY USER VIEW ===================== */

  @GetMapping("/user/years")
  @PreAuthorize(CAN_MANAGE_SALARIES)
  public List<YearRow> userYears(@RequestParam Long userId) {
    return yearsForUser(userId);
  }

  @GetMapping("/me/years")
  @PreAuthorize("isAuthenticated()")
  public ResponseEntity<List<YearRow>> myYears(Authentication auth) {
    return ResponseEntity.ok()
        .cacheControl(org.springframework.http.CacheControl.maxAge(java.time.Duration.ofDays(1)).cachePrivate())
        .body(yearsForUser(currentUserId(auth)));
  }

  private List<YearRow> yearsForUser(Long userId) {
    return shiftRepo.findInRange(
            OffsetDateTime.parse("2000-01-01T00:00:00Z"),
            OffsetDateTime.now().plusYears(1),
            null,
            userId
        ).stream()
        .map(s -> s.getStartAt().getYear())
        .distinct()
        .sorted(Comparator.reverseOrder())
        .map(YearRow::new)
        .toList();
  }

  @GetMapping("/user/months")
  @PreAuthorize(CAN_MANAGE_SALARIES)
  public List<MonthRow> userMonths(@RequestParam Long userId, @RequestParam int year) {
    return monthsForUser(userId, year);
  }

  @GetMapping("/me/months")
  @PreAuthorize("isAuthenticated()")
  public ResponseEntity<List<MonthRow>> myMonths(@RequestParam int year, Authentication auth) {
    return ResponseEntity.ok()
        .cacheControl(org.springframework.http.CacheControl.maxAge(java.time.Duration.ofHours(1)).cachePrivate())
        .body(monthsForUser(currentUserId(auth), year));
  }

  private List<MonthRow> monthsForUser(Long userId, int year) {
    OffsetDateTime start = OffsetDateTime.parse(year + "-01-01T00:00:00Z");
    OffsetDateTime end = OffsetDateTime.parse((year + 1) + "-01-01T00:00:00Z");

    return shiftRepo.findInRange(start, end, null, userId)
        .stream()
        .map(this::toUserLine)
        .collect(Collectors.groupingBy(l -> l.startAt().getMonthValue()))
        .entrySet().stream()
        .map(e -> new MonthRow(
            year,
            e.getKey(),
            e.getValue().stream().mapToDouble(ShiftLine::hours).sum(),
            e.getValue().stream().map(ShiftLine::cost).reduce(BigDecimal.ZERO, BigDecimal::add)
        ))
        .sorted((a, b) -> b.month() - a.month())
        .toList();
  }

  @GetMapping("/user/month")
  @PreAuthorize(CAN_MANAGE_SALARIES)
  public List<DayRow> userMonthDays(@RequestParam Long userId, @RequestParam int year, @RequestParam int month) {
    return monthDaysForUser(userId, year, month);
  }

  @GetMapping("/me/month")
  @PreAuthorize("isAuthenticated()")
  public List<DayRow> myMonthDays(
      @RequestParam int year,
      @RequestParam int month,
      Authentication auth
  ) {
    return monthDaysForUser(currentUserId(auth), year, month);
  }

  private List<DayRow> monthDaysForUser(Long userId, int year, int month) {
    String mm = String.format("%02d", month);
    OffsetDateTime start = OffsetDateTime.parse(year + "-" + mm + "-01T00:00:00Z");
    OffsetDateTime end = month == 12
        ? OffsetDateTime.parse((year + 1) + "-01-01T00:00:00Z")
        : OffsetDateTime.parse(year + "-" + String.format("%02d", month + 1) + "-01T00:00:00Z");

    return shiftRepo.findInRange(start, end, null, userId)
        .stream().map(this::toUserLine)
        .collect(Collectors.groupingBy(l -> l.startAt().toLocalDate().toString()))
        .entrySet().stream()
        .map(e -> new DayRow(
            e.getKey(),
            e.getValue().stream().map(ShiftLine::startAt).min(Comparator.naturalOrder()).orElse(null),
            e.getValue().stream().map(ShiftLine::endAt).max(Comparator.naturalOrder()).orElse(null),
            e.getValue().stream().mapToDouble(ShiftLine::hours).sum(),
            e.getValue().stream().map(ShiftLine::cost).reduce(BigDecimal.ZERO, BigDecimal::add)
        ))
        .sorted((a, b) -> b.dayKey().compareTo(a.dayKey()))
        .toList();
  }

  /* ===================== HELPERS ===================== */

  private Long currentUserId(Authentication auth) {
    String username = auth.getName();
    return userRepo.findByUsername(username)
        .orElseThrow(() -> new IllegalArgumentException("User not found: " + username))
        .getId();
  }

  private Map<Long, UserProfileRepository.UserProfileSummary> profilesForUsers(List<Long> userIds) {
    List<Long> uniqueUserIds = userIds.stream().distinct().toList();
    if (uniqueUserIds.isEmpty()) return Map.of();
    return profileRepo.findSummariesByUserIdIn(uniqueUserIds).stream()
        .collect(Collectors.toMap(UserProfileRepository.UserProfileSummary::getUserId, profile -> profile));
  }

  private ShiftLine toUserLine(ScheduleShift s) {
    var profile = profileRepo.findByUserId(s.getUser().getId()).orElse(null);
    return toUserLine(s, profile == null ? null : summaryOf(profile));
  }

  private UserProfileRepository.UserProfileSummary summaryOf(UserProfile profile) {
    return new UserProfileRepository.UserProfileSummary() {
      @Override public Long getUserId() { return profile.getUser().getId(); }
      @Override public String getFullName() { return profile.getFullName(); }
      @Override public BigDecimal getHourlyCost() { return profile.getHourlyCost(); }
      @Override public String getPayType() { return profile.getPayType(); }
      @Override public BigDecimal getMonthlySalary() { return profile.getMonthlySalary(); }
    };
  }

  private ShiftLine toUserLine(ScheduleShift s, UserProfileRepository.UserProfileSummary profile) {
    var p = s.getPharmacy();
    var u = s.getUser();

    BigDecimal hourly = s.getHourlyCostSnapshot() != null
        ? s.getHourlyCostSnapshot()
        : (profile != null && profile.getHourlyCost() != null ? profile.getHourlyCost() : BigDecimal.ZERO);

    double hours = Duration.between(s.getStartAt(), s.getEndAt()).toMinutes() / 60.0;
    BigDecimal cost = shiftGross(s.getStartAt(), s.getEndAt(), hourly);

    String fullName = (profile != null && profile.getFullName() != null && !profile.getFullName().isBlank())
        ? profile.getFullName()
        : u.getUsername();

    return new ShiftLine(
        s.getId(), p.getId(), p.getName(),
        u.getId(), fullName,
        s.getStartAt(), s.getEndAt(),
        hours, hourly, cost
    );
  }

  private ShiftLine toStaffLine(StaffShift s) {
    var profile = profileRepo.findByUserId(s.getUser().getId()).orElse(null);
    return toStaffLine(s, profile == null ? null : summaryOf(profile));
  }

  private ShiftLine toStaffLine(StaffShift s, UserProfileRepository.UserProfileSummary profile) {
    var u = s.getUser();

    BigDecimal hourly = (profile != null && profile.getHourlyCost() != null)
        ? profile.getHourlyCost()
        : BigDecimal.ZERO;

    double hours = Duration.between(s.getStartAt(), s.getEndAt()).toMinutes() / 60.0;
    BigDecimal cost = shiftGross(s.getStartAt(), s.getEndAt(), hourly);

    String fullName = (profile != null && profile.getFullName() != null && !profile.getFullName().isBlank())
        ? profile.getFullName()
        : u.getUsername();

    return new ShiftLine(
        s.getId(), 0L, "Staff",
        u.getId(), fullName,
        s.getStartAt(), s.getEndAt(),
        hours, hourly, cost
    );
  }

  /** Matches the base + Saturday 50% + Sunday 100% gross calculation in PayrollService. */
  private BigDecimal shiftGross(OffsetDateTime startAt, OffsetDateTime endAt, BigDecimal hourlyCost) {
    if (startAt == null || endAt == null || !endAt.isAfter(startAt)) return BigDecimal.ZERO;

    Instant cursor = startAt.toInstant();
    Instant end = endAt.toInstant();
    BigDecimal gross = BigDecimal.ZERO;
    while (cursor.isBefore(end)) {
      ZonedDateTime local = cursor.atZone(STOCKHOLM);
      Instant nextMidnight = local.toLocalDate().plusDays(1).atStartOfDay(STOCKHOLM).toInstant();
      Instant segmentEnd = end.isBefore(nextMidnight) ? end : nextMidnight;
      BigDecimal segmentHours = BigDecimal.valueOf(Duration.between(cursor, segmentEnd).toMillis())
          .divide(BigDecimal.valueOf(3_600_000), 6, RoundingMode.HALF_UP);
      BigDecimal segmentPay = hourlyCost.multiply(segmentHours);
      gross = gross.add(segmentPay);
      if (local.getDayOfWeek() == DayOfWeek.SATURDAY) {
        gross = gross.add(segmentPay.multiply(new BigDecimal("0.5")));
      } else if (local.getDayOfWeek() == DayOfWeek.SUNDAY) {
        gross = gross.add(segmentPay);
      }
      cursor = segmentEnd;
    }
    return gross;
  }

  private List<PharmacySummary> summarizeByPharmacy(
      List<ShiftLine> lines, Map<Long, UserProfileRepository.UserProfileSummary> profilesByUserId) {
    Map<Long, List<ShiftLine>> byPharmacy = lines.stream()
        .collect(Collectors.groupingBy(ShiftLine::pharmacyId));

    List<PharmacySummary> out = new ArrayList<>();

    for (var entry : byPharmacy.entrySet()) {
      List<ShiftLine> pLines = entry.getValue();
      String pharmacyName = pLines.get(0).pharmacyName();

      Map<Long, List<ShiftLine>> byUser = pLines.stream()
          .collect(Collectors.groupingBy(ShiftLine::userId));

      List<UserSummary> users = new ArrayList<>();
      double totalHours = 0;
      BigDecimal totalCost = BigDecimal.ZERO;

      for (var ue : byUser.entrySet()) {
        List<ShiftLine> uLines = ue.getValue();
        String fullName = uLines.get(0).userFullName();

        double hours = uLines.stream().mapToDouble(ShiftLine::hours).sum();
        BigDecimal cost = uLines.stream().map(ShiftLine::cost).reduce(BigDecimal.ZERO, BigDecimal::add);

        // ✅ weighted hourly avg
        BigDecimal hourly = hours > 0
            ? cost.divide(BigDecimal.valueOf(hours), 2, RoundingMode.HALF_UP)
            : BigDecimal.ZERO;

        var profile = profilesByUserId.get(ue.getKey());
        users.add(new UserSummary(ue.getKey(), fullName, hours, hourly,
            profile != null ? profile.getPayType() : null,
            profile != null ? profile.getMonthlySalary() : null,
            cost));

        totalHours += hours;
        totalCost = totalCost.add(cost);
      }

      users.sort((a, b) -> b.totalCost().compareTo(a.totalCost()));
      out.add(new PharmacySummary(entry.getKey(), pharmacyName, totalHours, totalCost, users));
    }

    out.sort((a, b) -> b.totalCost().compareTo(a.totalCost()));
    return out;
  }
}
