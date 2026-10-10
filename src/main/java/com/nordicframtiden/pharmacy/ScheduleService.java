package com.nordicframtiden.pharmacy;

import com.nordicframtiden.security.model.AppUser;
import com.nordicframtiden.security.repo.AppUserRepository;
import com.nordicframtiden.security.service.UserService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.core.Authentication;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

@Service
public class ScheduleService {

    /** Stockholm clock for the past-shift lock (test override). */
    private final java.time.Clock clock;
    private final ScheduleShiftRepository shiftRepo;
    private final PharmacyRepository pharmacyRepo;
    private final AppUserRepository userRepo;
    private final UserService userService; // ✅ use service
    /** Nullable: unit tests construct the service without the policy. */
    private final com.nordicframtiden.gdpr.DeletionPolicy deletionPolicy;

    public ScheduleService(
            ScheduleShiftRepository shiftRepo,
            PharmacyRepository pharmacyRepo,
            AppUserRepository userRepo,
            UserService userService) {
        this(shiftRepo, pharmacyRepo, userRepo, userService, null, java.time.Clock.system(ShiftLockPolicy.ZONE));
    }

    @org.springframework.beans.factory.annotation.Autowired
    public ScheduleService(
            ScheduleShiftRepository shiftRepo,
            PharmacyRepository pharmacyRepo,
            AppUserRepository userRepo,
            UserService userService,
            com.nordicframtiden.gdpr.DeletionPolicy deletionPolicy,
            java.time.Clock clock) {
        this.shiftRepo = shiftRepo;
        this.pharmacyRepo = pharmacyRepo;
        this.userRepo = userRepo;
        this.userService = userService;
        this.deletionPolicy = deletionPolicy;
        this.clock = clock;
    }

    private java.time.LocalDate today() {
        return java.time.LocalDate.now(clock);
    }

    public List<ScheduleShift> listForUser(Long userId, Instant start, Instant end) {
        if (userId == null)
            throw new IllegalArgumentException("userId is required");
        if (start == null || end == null)
            throw new IllegalArgumentException("start/end are required");

        OffsetDateTime startAt = start.atOffset(ZoneOffset.UTC);
        OffsetDateTime endAt = end.atOffset(ZoneOffset.UTC);

        return shiftRepo.findInRange(startAt, endAt, null, userId);
    }

    public List<ScheduleShift> listRange(OffsetDateTime start, OffsetDateTime end, Long pharmacyId, Long userId) {
        return shiftRepo.findInRange(start, end, pharmacyId, userId);
    }

    public List<ScheduleShift> listForCurrentUser(Authentication auth, OffsetDateTime start, OffsetDateTime end) {
        String username = auth.getName();
        var user = userRepo.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        return shiftRepo.findInRange(start, end, null, user.getId());
    }

    // A day conflict is detected before anything is written, so it must not
    // mark a caller's transaction rollback-only: the schedule wizard catches
    // it to skip booked days, and would otherwise lose every shift on commit
    // (UnexpectedRollbackException) after the notifications already went out.
    @Transactional(noRollbackFor = ShiftConflictException.class)
    public ScheduleShift create(Long pharmacyId, Long userId,
            OffsetDateTime startAt, OffsetDateTime endAt, String note) {
        validateRange(startAt, endAt);

        if (pharmacyId == null)
            throw new IllegalArgumentException("pharmacyId is required");

        AppUser user = userRepo.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        // Past-shift lock: no new shifts on days that have already passed.
        if (ShiftLockPolicy.isLocked(startAt, today())) {
            throw new ShiftLockedException(ShiftLockPolicy.pastCreationMessage(startAt));
        }

        // Deletion policy: with an open deletion request only the current and
        // next month (already booked, payroll) may receive new shifts.
        if (deletionPolicy != null) {
            deletionPolicy.assertShiftsAllowed(user.getId(), startAt);
        }

        Pharmacy pharmacy = pharmacyRepo.findById(pharmacyId)
                .orElseThrow(() -> new IllegalArgumentException("Pharmacy not found"));

        // One shift per user per day: notify and reject the duplicate.
        assertNoShiftOnSameDay(user.getId(), startAt, endAt, null);

        // Freeze hourly pay for HOURLY employees. MONTHLY employees are still
        // schedulable, but their payroll comes from the fixed monthly salary,
        // so their shift must not carry a fabricated hourly rate.
        BigDecimal hourly = resolveHourlySnapshotOrThrow(user.getId());

        ScheduleShift s = new ScheduleShift();
        s.setPharmacy(pharmacy);
        s.setUser(user);
        s.setStartAt(startAt);
        s.setEndAt(endAt);
        s.setNote(note);

        // ✅ snapshot at creation time
        s.setHourlyCostSnapshot(hourly);

        return shiftRepo.save(s);
    }

    @Transactional
    public ScheduleShift update(Long id,
            Long pharmacyId,
            Long userId,
            OffsetDateTime startAt,
            OffsetDateTime endAt,
            String note) {

        ScheduleShift s = shiftRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Shift not found"));

        // Past-shift lock: already-worked shifts cannot be changed or removed.
        if (ShiftLockPolicy.isLocked(s.getStartAt(), today())) {
            throw new ShiftLockedException(ShiftLockPolicy.lockedMessage(s.getStartAt()));
        }

        if (pharmacyId != null) {
            Pharmacy pharmacy = pharmacyRepo.findById(pharmacyId)
                    .orElseThrow(() -> new IllegalArgumentException("Pharmacy not found"));
            s.setPharmacy(pharmacy);
        }

        if (userId != null) {
            AppUser user = userRepo.findById(userId)
                    .orElseThrow(() -> new IllegalArgumentException("User not found"));
            // Deletion policy applies when the move lands beyond the payroll window.
            if (deletionPolicy != null) {
                deletionPolicy.assertShiftsAllowed(user.getId(),
                        startAt != null ? startAt : s.getStartAt());
            }
            // Reassignment: the rate snapshot follows the employee, otherwise
            // salary reporting keeps billing the previous person's rate.
            if (!user.getId().equals(s.getUser().getId())) {
                s.setHourlyCostSnapshot(resolveHourlySnapshotOrThrow(user.getId()));
            }
            s.setUser(user);
        } else if (deletionPolicy != null && (startAt != null)) {
            deletionPolicy.assertShiftsAllowed(s.getUser().getId(), startAt);
        }

        // Past-shift lock: moving a shift into the past is a history edit too.
        // Check the NEW start — the current one was already checked above.
        if (ShiftLockPolicy.isLocked(startAt, today())) {
            throw new ShiftLockedException(ShiftLockPolicy.pastCreationMessage(startAt));
        }

        if (startAt != null)
            s.setStartAt(startAt);
        if (endAt != null)
            s.setEndAt(endAt);

        validateRange(s.getStartAt(), s.getEndAt());

        // One shift per user per day — ignore the shift being moved.
        assertNoShiftOnSameDay(s.getUser().getId(), s.getStartAt(), s.getEndAt(), id);

        if (note != null)
            s.setNote(note);

        return shiftRepo.save(s);
    }

    @Transactional
    public void delete(Long id) {
        // Past-shift lock: already-worked shifts cannot be removed.
        ScheduleShift s = shiftRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Shift not found"));
        if (ShiftLockPolicy.isLocked(s.getStartAt(), today())) {
            throw new ShiftLockedException(ShiftLockPolicy.lockedMessage(s.getStartAt()));
        }
        shiftRepo.deleteById(id);
    }

    /**
     * Resolves the employee's current shift snapshot. MONTHLY employees have
     * no hourly rate by design; their fixed salary is calculated separately
     * by payroll, so their shifts store a null hourly snapshot. HOURLY
     * employees still require a configured rate.
     */
    private BigDecimal resolveHourlySnapshotOrThrow(Long userId) {
        try {
            var profile = userService.getProfileByUserId(userId);
            if (profile == null) {
                throw new IllegalArgumentException("Användarens profil saknas.");
            }
            if ("MONTHLY".equalsIgnoreCase(profile.getPayType())) {
                if (profile.getMonthlySalary() == null || profile.getMonthlySalary().signum() < 0) {
                    throw new IllegalArgumentException(
                        "Användaren saknar månadslön — ange lönen innan arbetspass bokas.");
                }
                return null;
            }
            if (profile.getHourlyCost() == null) {
                throw new IllegalArgumentException(
                    "Användaren har ingen timkostnad satt — sätt lönen innan arbetspass bokas.");
            }
            return profile.getHourlyCost();
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Kunde inte läsa användarens profil för löneuppslagning.", e);
        }
    }

    private static void validateRange(OffsetDateTime startAt, OffsetDateTime endAt) {
        if (startAt == null || endAt == null)
            throw new IllegalArgumentException("Start/end required");
        if (!startAt.isBefore(endAt))
            throw new IllegalArgumentException("Invalid time range");
    }

    /** Stockholm timezone for the "same day" definition. */
    private static final java.time.ZoneId SCHEDULE_ZONE = java.time.ZoneId.of("Europe/Stockholm");

    /**
     * Rejects the shift when the user already has another shift whose
     * [start, end) window intersects the Stockholm calendar day of the new
     * shift. {@code excludeShiftId} lets an update ignore itself.
     */
    private void assertNoShiftOnSameDay(Long userId, OffsetDateTime startAt, OffsetDateTime endAt, Long excludeShiftId) {
        // Serialize shift writes per user: without the row lock two concurrent
        // creates both pass the check below and both insert.
        userRepo.lockForPayroll(userId);
        // Local Stockholm midnights (DST-safe: never +/- fixed 24h), and the
        // window covers EVERY day the shift touches (overnight shifts too):
        // from the start day's midnight to the day AFTER the last touched day.
        // The end is exclusive: a shift ending at 00:00 does not touch the next
        // day (otherwise the outcome depended on which shift was booked first).
        var dayStart = startAt.atZoneSameInstant(SCHEDULE_ZONE).toLocalDate().atStartOfDay(SCHEDULE_ZONE).toInstant().atOffset(ZoneOffset.UTC);
        var lastTouchedDay = endAt.minusNanos(1).atZoneSameInstant(SCHEDULE_ZONE).toLocalDate();
        var windowEnd = lastTouchedDay.plusDays(1).atStartOfDay(SCHEDULE_ZONE).toInstant().atOffset(ZoneOffset.UTC);
        var dayEnd = windowEnd.isAfter(dayStart) ? windowEnd : dayStart.plusDays(1);

        List<ScheduleShift> sameDay = shiftRepo.findByUserIdAndStartAtLessThanAndEndAtGreaterThan(userId, dayEnd, dayStart);
        // On create (excludeShiftId == null) any same-day shift conflicts;
        // on update the shift being moved is ignored.
        boolean conflict = sameDay.stream()
            .anyMatch(s -> excludeShiftId == null || !java.util.Objects.equals(s.getId(), excludeShiftId));
        if (conflict) {
            var day = startAt.atZoneSameInstant(SCHEDULE_ZONE).toLocalDate();
            throw new ShiftConflictException(
                "Användaren har redan ett arbetspass den " + day
                    + ". Endast ett arbetspass per användare och dag är tillåtet.");
        }
    }
}