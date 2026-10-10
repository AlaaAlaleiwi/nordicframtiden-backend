package com.nordicframtiden.service;

import com.nordicframtiden.availability.AvailabilityRequest;
import com.nordicframtiden.availability.AvailabilityRequestRepository;
import com.nordicframtiden.chat.ChatPushSender;
import com.nordicframtiden.chat.ChatPushSubscriptionRepository;
import com.nordicframtiden.pharmacy.Pharmacy;
import com.nordicframtiden.pharmacy.PharmacyRepository;
import com.nordicframtiden.pharmacy.ScheduleService;
import com.nordicframtiden.pharmacy.ScheduleShift;
import com.nordicframtiden.pharmacy.ScheduleShiftRepository;
import com.nordicframtiden.security.model.AppUser;
import com.nordicframtiden.security.model.Role;
import com.nordicframtiden.security.model.UserProfile;
import com.nordicframtiden.security.repo.AppUserRepository;
import com.nordicframtiden.security.repo.UserProfileRepository;
import com.nordicframtiden.settings.EmailService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Back end of the admin "simple schedule" wizard: the admin picks a period
 * (day/week/month), a pharmacy and pharmacists; the service reports which
 * pharmacists have approved availability for the period and which days are
 * already booked, then creates one shift per day and pharmacist with a
 * default 09:00-17:00 time window, and confirms by push notification and a
 * schedule PDF email to every affected pharmacist.
 *
 * Shift creation goes through {@link ScheduleService#create} so every house
 * rule (past-shift lock, deletion policy, one shift per user per day,
 * hourly-rate snapshot) keeps applying. Conflicting days are skipped and
 * reported instead of failing the whole wizard.
 */
@Service
public class ScheduleWizardService {

  private static final Logger log = LoggerFactory.getLogger(ScheduleWizardService.class);
  private static final ZoneId STOCKHOLM = ZoneId.of("Europe/Stockholm");
  private static final DateTimeFormatter ISO_DAY = DateTimeFormatter.ISO_LOCAL_DATE;
  private static final DateTimeFormatter WEEKDAY_SV =
      DateTimeFormatter.ofPattern("EEEE", Locale.forLanguageTag("sv-SE"));
  private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm");

  private final ScheduleService scheduleService;
  private final ScheduleShiftRepository shiftRepo;
  private final PharmacyRepository pharmacyRepo;
  private final AppUserRepository userRepo;
  private final UserProfileRepository profileRepo;
  private final AvailabilityRequestRepository availabilityRepo;
  private final SchedulePdfBuilder pdfBuilder;
  private final EmailService emailService;
  private final ChatPushSender pushSender;
  private final ChatPushSubscriptionRepository pushSubscriptions;

  public ScheduleWizardService(ScheduleService scheduleService,
      ScheduleShiftRepository shiftRepo,
      PharmacyRepository pharmacyRepo,
      AppUserRepository userRepo,
      UserProfileRepository profileRepo,
      AvailabilityRequestRepository availabilityRepo,
      SchedulePdfBuilder pdfBuilder,
      EmailService emailService,
      ChatPushSender pushSender,
      ChatPushSubscriptionRepository pushSubscriptions) {
    this.scheduleService = scheduleService;
    this.shiftRepo = shiftRepo;
    this.pharmacyRepo = pharmacyRepo;
    this.userRepo = userRepo;
    this.profileRepo = profileRepo;
    this.availabilityRepo = availabilityRepo;
    this.pdfBuilder = pdfBuilder;
    this.emailService = emailService;
    this.pushSender = pushSender;
    this.pushSubscriptions = pushSubscriptions;
  }

  /* ===================== Public contracts ===================== */

  public enum Period { DAY, WEEK, MONTH }

  /** One pharmacist row of the wizard's option step. */
  public record PharmacistOption(Long userId, String fullName, String username,
      boolean available, String availabilityNote) {
  }

  /** One day where a requested pharmacist is already booked. */
  public record DayConflict(LocalDate date, Long pharmacistId, String pharmacistName,
      OffsetDateTime existingStartAt, OffsetDateTime existingEndAt) {
  }

  /** Reply of the option step: period, pharmacy, pharmacists and conflicts. */
  public record WizardOptions(String period, LocalDate startDate, LocalDate endDate,
      Long pharmacyId, List<PharmacistOption> pharmacists, List<DayConflict> conflicts) {
  }

  /** Reply of the confirm step. */
  public record WizardConfirmResult(int createdCount, List<DayConflict> conflicts,
      String pdfBase64, List<Long> notifiedUserIds, boolean emailSent) {
  }

  /**
   * One day × pharmacist row of the confirm request. Times are optional:
   * null falls back to the default 09:00-17:00 window.
   */
  public record Assignment(LocalDate date, Long pharmacistId,
      LocalTime startTime, LocalTime endTime) {

    /** Convenience for callers using the default 09:00-17:00 window. */
    public Assignment(LocalDate date, Long pharmacistId) {
      this(date, pharmacistId, null, null);
    }
  }

  /* ===================== Option step ===================== */

  /**
   * Lists the pharmacists for the period with their availability for the
   * 09:00-17:00 window, plus every day where a requested pharmacist is
   * already booked (any pharmacy).
   */
  @Transactional(readOnly = true)
  public WizardOptions options(String period, LocalDate anchorDate, Long pharmacyId) {
    LocalDate[] range = periodOf(period, anchorDate);
    LocalDate start = range[0];
    LocalDate end = range[1];
    LocalTime from = defaultStartTime();
    LocalTime to = defaultEndTime();

    // Availability: PENDING + APPROVED overlap the range. APPROVED makes a
    // pharmacist "available"; the note surfaces what is pending.
    Map<Long, String> available = new HashMap<>();
    for (AvailabilityRequest request : availabilityRepo.findByStatusInAndOverlapping(
        List.of(AvailabilityRequest.Status.PENDING, AvailabilityRequest.Status.APPROVED),
        start, end)) {
      String note = request.getStatus() == AvailabilityRequest.Status.APPROVED ? "APPROVED" : "PENDING";
      LocalDate fromDay = request.getStartDate().isBefore(start) ? start : request.getStartDate();
      LocalDate toDay = request.getEndDate().isAfter(end) ? end : request.getEndDate();
      boolean coversWindow =
          (request.getStartTime() == null || !request.getStartTime().isAfter(from))
              && (request.getEndTime() == null || !request.getEndTime().isBefore(to));
      for (LocalDate day = fromDay; !day.isAfter(toDay); day = day.plusDays(1)) {
        if (coversWindow) {
          available.merge(request.getUser().getId(), note, (a, b) -> a.equals(b) ? a : a + "+" + b);
        }
      }
    }

    List<PharmacistOption> pharmacists = userRepo.findAllByRole(Role.USER).stream()
        .filter(AppUser::isEnabled)
        .map(user -> {
          String name = profileRepo.findByUserId(user.getId())
              .map(UserProfile::getFullName)
              .filter(fullName -> fullName != null && !fullName.isBlank())
              .orElse(user.getUsername());
          return new PharmacistOption(user.getId(), name, user.getUsername(),
              available.containsKey(user.getId()), available.get(user.getId()));
        })
        .sorted((a, b) -> {
          if (a.available() != b.available()) return a.available() ? -1 : 1;
          return a.fullName().compareToIgnoreCase(b.fullName());
        })
        .toList();

    return new WizardOptions(period, start, end, pharmacyId, pharmacists, conflicts(start, end, pharmacists));
  }

  /* ===================== Confirm step ===================== */

  /**
   * Creates one shift per assignment with the default 09:00-17:00 window,
   * then pushes a notification and emails the schedule PDF (one combined
   * PDF) to every affected pharmacist. Booked days are skipped and reported.
   */
  @Transactional
  public WizardConfirmResult confirm(String period, LocalDate anchorDate, Long pharmacyId,
      List<Assignment> assignments, boolean sendEmail) {
    if (assignments == null || assignments.isEmpty()) {
      throw new IllegalArgumentException("No days selected");
    }
    if (pharmacyId == null) {
      throw new IllegalArgumentException("pharmacyId is required");
    }
    Pharmacy pharmacy = pharmacyRepo.findById(pharmacyId)
        .orElseThrow(() -> new IllegalArgumentException("Pharmacy not found"));

    LocalDate[] range = periodOf(period, anchorDate);
    LocalDate start = range[0];
    LocalDate end = range[1];

    Map<Long, AppUser> pharmacists = new LinkedHashMap<>();
    for (Assignment assignment : assignments) {
      if (assignment.date() == null || assignment.pharmacistId() == null) {
        throw new IllegalArgumentException("Every assignment needs a date and a pharmacist");
      }
      if (assignment.date().isBefore(start) || assignment.date().isAfter(end)) {
        throw new IllegalArgumentException(
            "Assignment date " + assignment.date() + " is outside the period " + start + " – " + end);
      }
      AppUser user = userRepo.findById(assignment.pharmacistId())
          .orElseThrow(() -> new IllegalArgumentException(
              "Pharmacist " + assignment.pharmacistId() + " not found"));
      if (user.getRoles() == null || !user.getRoles().contains(Role.USER)) {
        throw new IllegalArgumentException(
            "User " + assignment.pharmacistId() + " is not a pharmacist");
      }
      pharmacists.put(user.getId(), user);
    }

    // Days already booked (any pharmacy) for the assigned pharmacists.
    List<DayConflict> conflicts = new ArrayList<>(conflicts(start, end,
        pharmacists.values().stream()
            .map(user -> new PharmacistOption(user.getId(), displayName(user),
                user.getUsername(), false, null))
            .toList()));

    List<ScheduleShift> created = new ArrayList<>();
    for (Assignment assignment : assignments) {
      LocalTime from = assignment.startTime() != null
          ? assignment.startTime() : defaultStartTime();
      LocalTime to = assignment.endTime() != null
          ? assignment.endTime() : defaultEndTime();
      if (!from.isBefore(to)) {
        throw new IllegalArgumentException(
            "Shift start must be before end on " + assignment.date());
      }
      OffsetDateTime shiftStart = assignment.date().atTime(from).atZone(STOCKHOLM).toOffsetDateTime();
      OffsetDateTime shiftEnd = assignment.date().atTime(to).atZone(STOCKHOLM).toOffsetDateTime();
      try {
        created.add(scheduleService.create(pharmacyId, assignment.pharmacistId(),
            shiftStart, shiftEnd, null));
      } catch (com.nordicframtiden.pharmacy.ShiftConflictException exception) {
        // One shift per user per day — skip and report instead of failing
        // the whole wizard.
        conflicts.add(new DayConflict(assignment.date(), assignment.pharmacistId(),
            displayName(pharmacists.get(assignment.pharmacistId())), null, null));
      }
    }

    if (created.isEmpty()) {
      return new WizardConfirmResult(0, dedupe(conflicts), null, List.of(), false);
    }

    byte[] pdf = pdfBuilder.build(pharmacy.getName(), start, end, pdfLines(created));
    List<Long> notified = notifyPharmacists(created, start, end);
    boolean emailSent = false;
    if (sendEmail) {
      OffsetDateTime periodStart = start.atTime(defaultStartTime()).atZone(STOCKHOLM).toOffsetDateTime();
      OffsetDateTime periodEnd = end.atTime(defaultEndTime()).atZone(STOCKHOLM).toOffsetDateTime();
      emailSent = emailSchedule(pharmacists, pdf, periodStart, periodEnd);
    }

    log.info("Schedule wizard created {} shift(s), reported {} conflict(s), and queued notifications for {} recipient(s)",
        created.size(), dedupe(conflicts).size(), notified.size());
    return new WizardConfirmResult(created.size(), dedupe(conflicts),
        java.util.Base64.getEncoder().encodeToString(pdf), notified, emailSent);
  }

  /* ===================== Internals ===================== */

  LocalTime defaultStartTime() {
    return LocalTime.of(9, 0);
  }

  LocalTime defaultEndTime() {
    return LocalTime.of(17, 0);
  }

  /** DAY → same date; WEEK → Monday..Sunday; MONTH → first..last day. */
  LocalDate[] periodOf(String period, LocalDate anchorDate) {
    if (anchorDate == null) throw new IllegalArgumentException("date is required");
    Period type;
    try {
      type = Period.valueOf(period.toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("period must be DAY, WEEK or MONTH");
    }
    return switch (type) {
      case DAY -> new LocalDate[]{anchorDate, anchorDate};
      case WEEK -> {
        LocalDate monday = anchorDate.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        yield new LocalDate[]{monday, monday.plusDays(6)};
      }
      case MONTH -> new LocalDate[]{anchorDate.withDayOfMonth(1),
          anchorDate.withDayOfMonth(anchorDate.lengthOfMonth())};
    };
  }

  /** Every booked day of a requested pharmacist within [start, end]. */
  private List<DayConflict> conflicts(LocalDate start, LocalDate end, List<PharmacistOption> requested) {
    Set<Long> wanted = Set.copyOf(requested.stream().map(PharmacistOption::userId).toList());
    if (wanted.isEmpty()) return List.of();
    Map<Long, String> names = new HashMap<>();
    requested.forEach(option -> names.put(option.userId(), option.fullName()));

    OffsetDateTime scanStart = start.atStartOfDay(STOCKHOLM).toOffsetDateTime();
    OffsetDateTime scanEnd = end.plusDays(1).atStartOfDay(STOCKHOLM).toOffsetDateTime();

    List<DayConflict> conflicts = new ArrayList<>();
    for (ScheduleShift shift : shiftRepo.findInRange(scanStart, scanEnd, null, null)) {
      Long userId = shift.getUser().getId();
      if (!wanted.contains(userId)) continue;
      LocalDate firstDay = shift.getStartAt().atZoneSameInstant(STOCKHOLM).toLocalDate();
      LocalDate lastDay = shift.getEndAt().atZoneSameInstant(STOCKHOLM).toLocalDate();
      for (LocalDate day = firstDay.isBefore(start) ? start : firstDay;
          !day.isAfter(lastDay) && !day.isAfter(end);
          day = day.plusDays(1)) {
        conflicts.add(new DayConflict(day, userId, names.get(userId),
            shift.getStartAt(), shift.getEndAt()));
      }
    }
    return conflicts;
  }

  private List<SchedulePdfBuilder.DayLine> pdfLines(List<ScheduleShift> shifts) {
    return shifts.stream()
        .sorted(java.util.Comparator.comparing(ScheduleShift::getStartAt))
        .map(shift -> {
          LocalDate day = shift.getStartAt().atZoneSameInstant(STOCKHOLM).toLocalDate();
          double hours = java.time.Duration.between(
              shift.getStartAt(), shift.getEndAt()).toMinutes() / 60.0;
          return new SchedulePdfBuilder.DayLine(
              day.format(ISO_DAY),
              day.format(WEEKDAY_SV),
              displayName(shift.getUser()),
              shift.getStartAt().atZoneSameInstant(STOCKHOLM).format(CLOCK),
              shift.getEndAt().atZoneSameInstant(STOCKHOLM).format(CLOCK),
              String.format(Locale.ROOT, "%.2f", hours));
        })
        .toList();
  }

  /** Push "your schedule is ready" to every device of the affected users. */
  private List<Long> notifyPharmacists(List<ScheduleShift> created, LocalDate start, LocalDate end) {
    List<Long> notified = new ArrayList<>();
    for (Long userId : created.stream().map(shift -> shift.getUser().getId()).distinct().toList()) {
      String username = userRepo.findById(userId).map(AppUser::getUsername).orElse(null);
      if (username == null) continue;
      var data = Map.of(
          "title", "Nordic Framtiden",
          "body", "Your schedule " + start.format(ISO_DAY) + " – " + end.format(ISO_DAY) + " is ready",
          "url", "/schedule",
          "type", "schedule.confirmed",
          "startDate", start.format(ISO_DAY),
          "endDate", end.format(ISO_DAY));
      var subscriptions = pushSubscriptions.findByUserUsernameIn(List.of(username));
      if (subscriptions.isEmpty()) continue;
      try {
        subscriptions.forEach(subscription ->
            pushSender.send(subscription.getFirebaseInstallationId(), data));
        notified.add(userId);
      } catch (RuntimeException e) {
        log.warn("Schedule notification could not be queued (user id redacted): {}",
            e.getClass().getSimpleName());
      }
    }
    return notified;
  }

  /** One combined PDF to every affected pharmacist with an email on file. */
  private boolean emailSchedule(Map<Long, AppUser> pharmacists, byte[] pdf,
      OffsetDateTime start, OffsetDateTime end) {
    boolean anySent = false;
    for (AppUser user : pharmacists.values()) {
      String email = profileRepo.findByUserId(user.getId())
          .map(UserProfile::getEmail)
          .filter(value -> value != null && !value.isBlank() && value.contains("@"))
          .orElse(null);
      if (email == null) continue;
      try {
        anySent |= emailService.sendSchedulePdfEmail(email, displayName(user), pdf,
            "Schema", start, end);
      } catch (RuntimeException e) {
        log.warn("Schedule email failed (user id redacted): {}", e.getClass().getSimpleName());
      }
    }
    return anySent;
  }

  private String displayName(AppUser user) {
    return profileRepo.findByUserId(user.getId())
        .map(UserProfile::getFullName)
        .filter(fullName -> fullName != null && !fullName.isBlank())
        .orElse(user.getUsername());
  }

  private List<DayConflict> dedupe(List<DayConflict> conflicts) {
    record Key(LocalDate date, Long pharmacistId) {}
    return conflicts.stream()
        .sorted(java.util.Comparator.comparing(DayConflict::date)
            .thenComparing(DayConflict::pharmacistId))
        .collect(java.util.stream.Collectors.toMap(
            conflict -> new Key(conflict.date(), conflict.pharmacistId()),
            conflict -> conflict,
            (first, second) -> first,
            java.util.LinkedHashMap::new))
        .values().stream().toList();
  }
}
