package com.nordicframtiden.gdpr;

import com.nordicframtiden.company.StaffShiftRepository;
import com.nordicframtiden.pharmacy.ScheduleShiftRepository;
import com.nordicframtiden.service.PayrollCalendar;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Deletion policy for pharmacists (users): deleting a user wipes their
 * schedule history, which payroll needs — they work this month and get paid
 * next month. While a deletion request is open:
 *
 * - Existing shifts in the current month (M) and next month (M+1) are kept
 *   for payroll.
 * - NEW shifts may only be booked inside M and M+1; anything starting in M+2
 *   or later is refused (nothing may outlive the scheduled deletion).
 * - The suggested deletion date is the end of the month AFTER the last shift
 *   month (all payroll for that month has then been paid), or MIN_GRACE_DAYS
 *   out when the user has no shifts in M or M+1. Admins may postpone the
 *   approved date, never shorten it below the suggestion.
 *
 * All month arithmetic uses Europe/Stockholm, matching the payroll rules.
 */
@Service
public class DeletionPolicy {

  public static final ZoneId ZONE = ZoneId.of("Europe/Stockholm");

  /** How far ahead pre-booked shifts are considered for the deletion date. */
  private static final int SHIFT_HORIZON_YEARS = 5;

  /** Requests that block new shifts / direct deletion until closed. */
  private static final List<String> OPEN_STATUSES = List.of(
      GdprDeletionRequest.STATUS_PENDING, GdprDeletionRequest.STATUS_APPROVED);

  private final GdprDeletionRequestRepository requests;
  private final ScheduleShiftRepository scheduleShifts;
  private final StaffShiftRepository staffShifts;
  private final Clock clock;

  @Autowired
  public DeletionPolicy(
      GdprDeletionRequestRepository requests,
      ScheduleShiftRepository scheduleShifts,
      StaffShiftRepository staffShifts) {
    this(requests, scheduleShifts, staffShifts, Clock.system(ZONE));
  }

  /** Test clock so month boundaries are deterministic in unit tests. */
  DeletionPolicy(
      GdprDeletionRequestRepository requests,
      ScheduleShiftRepository scheduleShifts,
      StaffShiftRepository staffShifts,
      Clock clock) {
    this.requests = requests;
    this.scheduleShifts = scheduleShifts;
    this.staffShifts = staffShifts;
    this.clock = clock;
  }

  // ---------- Open request ----------

  /** The user's open (PENDING/APPROVED) deletion request, if any. */
  public Optional<GdprDeletionRequest> openRequest(Long userId) {
    return requests.findTopByUserIdAndStatusInOrderByCreatedAtDesc(userId, OPEN_STATUSES);
  }

  public boolean hasOpenRequest(Long userId) {
    return openRequest(userId).isPresent();
  }

  // ---------- Shift block ----------

  /**
   * The window (first day of M .. last day of M+1) in which new shifts are
   * still allowed while a deletion request is open; null when no request is
   * open. Existing shifts inside the window are never touched — payroll.
   * Once approved, the window also ends with the last work month whose
   * payslip is delivered before the scheduled deletion date — a shift booked
   * after approval must never be wiped before it is paid.
   */
  public ShiftBlock shiftBlock(Long userId) {
    Optional<GdprDeletionRequest> open = openRequest(userId);
    if (open.isEmpty()) {
      return null;
    }
    LocalDate today = LocalDate.now(clock);
    LocalDate until = today.plusMonths(1).with(TemporalAdjusters.lastDayOfMonth());
    LocalDate scheduled = open.get().getScheduledDate();
    if (scheduled != null) {
      LocalDate lastPaid = lastWorkMonthPaidBefore(scheduled).atEndOfMonth();
      if (lastPaid.isBefore(until)) until = lastPaid;
    }
    return new ShiftBlock(today.withDayOfMonth(1), until);
  }

  /**
   * Latest work month whose payslip ready date falls strictly before
   * {@code deletionDate}. Strictly: the deletion job (03:30) runs before the
   * payslip delivery job (04:00) on the same day.
   */
  static YearMonth lastWorkMonthPaidBefore(LocalDate deletionDate) {
    YearMonth candidate = YearMonth.from(deletionDate).minusMonths(1);
    return PayrollCalendar.readyDateFor(candidate).isBefore(deletionDate)
        ? candidate
        : candidate.minusMonths(1);
  }

  public record ShiftBlock(LocalDate blockFrom, LocalDate blockUntil) {}

  /**
   * Refuses shifts STARTING on/after the last day of M+1 (Stockholm day of
   * the shift start decides). Shifts inside the window are allowed: they are
   * paid out at the latest by the end of M+2, before any deletion runs.
   */
  public void assertShiftsAllowed(Long userId, OffsetDateTime startAt) {
    ShiftBlock block = shiftBlock(userId);
    if (block == null) {
      return;
    }
    LocalDate startDay = startAt.atZoneSameInstant(ZONE).toLocalDate();
    if (startDay.isAfter(block.blockUntil())) {
      throw new DeletionShiftBlockException(
          "Användaren har en öppen raderingsbegäran. Nya arbetspass kan bara läggas till fram till och med "
              + block.blockUntil()
              + " — raderingen sker efter sista lönemånaden.");
    }
  }

  // ---------- Deletion dates ----------

  /**
   * End of the month after the user's last shift month (any shift from M
   * onwards — including ones booked beyond M+1 before the request was filed),
   * or MIN_GRACE_DAYS out when there are none — every hour is then paid
   * before the account disappears.
   */
  public LocalDate suggestedDeletionDate(Long userId) {
    LocalDate today = LocalDate.now(clock);
    OffsetDateTime from = today.withDayOfMonth(1).atStartOfDay(ZONE).toOffsetDateTime();
    OffsetDateTime to = today.plusYears(SHIFT_HORIZON_YEARS).withDayOfMonth(1).atStartOfDay(ZONE).toOffsetDateTime();

    Optional<OffsetDateTime> lastStart = Stream.concat(
        scheduleShifts.findInRange(from, to, null, userId).stream()
            .map(s -> s.getStartAt()),
        staffShifts.findInRange(from, to, userId).stream()
            .map(s -> s.getStartAt()))
        .max(Comparator.naturalOrder());

    if (lastStart.isEmpty()) {
      return today.plusDays(GdprDeletionRequest.MIN_GRACE_DAYS);
    }
    LocalDate lastShiftDay = lastStart.get().atZoneSameInstant(ZONE).toLocalDate();
    return lastShiftDay.withDayOfMonth(1).plusMonths(1)
        .with(TemporalAdjusters.lastDayOfMonth());
  }

  /**
   * The date an approval should actually use: the admin's requested date,
   * but never before the policy suggestion (null defaults to it).
   */
  public LocalDate effectiveApprovalDate(Long userId, LocalDate requested) {
    LocalDate suggested = suggestedDeletionDate(userId);
    if (requested == null || requested.isBefore(suggested)) {
      return suggested;
    }
    return requested;
  }

  // ---------- Direct deletion guards ----------

  /** True when the user has any shift starting in the current month (M). */
  public boolean hasShiftsInCurrentMonth(Long userId) {
    LocalDate today = LocalDate.now(clock);
    OffsetDateTime from = today.withDayOfMonth(1).atStartOfDay(ZONE).toOffsetDateTime();
    OffsetDateTime to = from.plusMonths(1);
    return !scheduleShifts.findInRange(from, to, null, userId).isEmpty()
        || !staffShifts.findInRange(from, to, userId).isEmpty();
  }

  /**
   * True while the user has shifts whose pay has not been delivered yet: any
   * shift starting this month, or last month until the day after its payslip
   * ready date. Deleting the account before then wipes unpaid payroll records.
   */
  public boolean hasUnpaidShifts(Long userId) {
    if (hasShiftsInCurrentMonth(userId)) {
      return true;
    }
    LocalDate today = LocalDate.now(clock);
    YearMonth previous = YearMonth.from(today).minusMonths(1);
    if (today.isAfter(PayrollCalendar.readyDateFor(previous))) {
      return false;
    }
    OffsetDateTime from = previous.atDay(1).atStartOfDay(ZONE).toOffsetDateTime();
    OffsetDateTime to = from.plusMonths(1);
    return !scheduleShifts.findInRange(from, to, null, userId).isEmpty()
        || !staffShifts.findInRange(from, to, userId).isEmpty();
  }

  // ---------- UI info ----------

  /** Everything the deletion-requests queue, profiles and People rows show. */
  public record Info(
      Long userId,
      String status,
      LocalDate scheduledDate,
      LocalDate suggestedDeletionDate,
      boolean shiftsThisMonth,
      LocalDate blockFrom,
      LocalDate blockUntil) {}

  public Info infoFor(Long userId) {
    Optional<GdprDeletionRequest> open = openRequest(userId);
    ShiftBlock block = open.isPresent() ? shiftBlock(userId) : null;
    return new Info(
        userId,
        open.map(GdprDeletionRequest::getStatus).orElse(null),
        open.map(GdprDeletionRequest::getScheduledDate).orElse(null),
        suggestedDeletionDate(userId),
        hasShiftsInCurrentMonth(userId),
        block == null ? null : block.blockFrom(),
        block == null ? null : block.blockUntil());
  }
}
