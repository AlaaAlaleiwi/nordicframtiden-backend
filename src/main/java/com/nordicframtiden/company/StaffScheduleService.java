package com.nordicframtiden.company;

import com.nordicframtiden.security.model.AppUser;
import com.nordicframtiden.security.repo.AppUserRepository;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;
import org.springframework.security.core.Authentication;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

@Service
public class StaffScheduleService {

  private final StaffShiftRepository repo;
  private final AppUserRepository userRepo;
  /** Nullable: unit tests construct the service without the policy. */
  private final com.nordicframtiden.gdpr.DeletionPolicy deletionPolicy;
  /** Stockholm clock for the past-shift lock (test override). */
  private final java.time.Clock clock;

  public StaffScheduleService(
      StaffShiftRepository repo,
      AppUserRepository userRepo,
      com.nordicframtiden.gdpr.DeletionPolicy deletionPolicy,
      java.time.Clock clock) {
    this.repo = repo;
    this.userRepo = userRepo;
    this.deletionPolicy = deletionPolicy;
    this.clock = clock;
  }

  private java.time.LocalDate today() {
    return java.time.LocalDate.now(clock);
  }

  public List<StaffShift> listRange(OffsetDateTime start, OffsetDateTime end, Long userId) {
    return repo.findInRange(start, end, userId);
  }

  public List<StaffShift> listForCurrentUser(Authentication auth, OffsetDateTime start, OffsetDateTime end) {
    if (auth == null || auth.getName() == null) {
      throw new IllegalArgumentException("Not authenticated");
    }

    AppUser u = userRepo.findByUsername(auth.getName())
        .orElseThrow(() -> new IllegalArgumentException("User not found"));

    return repo.findInRange(start, end, u.getId());
  }

  public List<StaffShift> listForUser(Long userId, Instant start, Instant end) {
    if (userId == null) {
      throw new IllegalArgumentException("userId is required");
    }
    if (start == null || end == null) {
      throw new IllegalArgumentException("start/end are required");
    }

    OffsetDateTime startAt = start.atOffset(ZoneOffset.UTC);
    OffsetDateTime endAt = end.atOffset(ZoneOffset.UTC);

    return repo.findInRange(startAt, endAt, userId);
  }

  @Transactional
  public StaffShift create(Long userId, OffsetDateTime startAt, OffsetDateTime endAt, String note) {
    if (userId == null)
      throw new IllegalArgumentException("userId is required");
    if (startAt == null || endAt == null)
      throw new IllegalArgumentException("startAt/endAt are required");
    if (!startAt.isBefore(endAt))
      throw new IllegalArgumentException("startAt must be before endAt");

    // Past-shift lock: no new shifts on days that have already passed.
    if (com.nordicframtiden.pharmacy.ShiftLockPolicy.isLocked(startAt, today())) {
      throw new com.nordicframtiden.pharmacy.ShiftLockedException(
          com.nordicframtiden.pharmacy.ShiftLockPolicy.pastCreationMessage(startAt));
    }

    var user = userRepo.findById(userId)
        .orElseThrow(() -> new IllegalArgumentException("User not found"));

    // Deletion policy: with an open deletion request only the current and
    // next month (already booked, payroll) may receive new shifts.
    if (deletionPolicy != null) {
      deletionPolicy.assertShiftsAllowed(user.getId(), startAt);
    }

    var s = new StaffShift();
    s.setUser(user);
    s.setStartAt(startAt);
    s.setEndAt(endAt);
    s.setNote(note);

    return repo.save(s);
  }

  @Transactional
  public StaffShift update(Long id, Long userId, OffsetDateTime startAt, OffsetDateTime endAt, String note) {
    var s = repo.findById(id).orElseThrow(() -> new IllegalArgumentException("Shift not found"));

    // Past-shift lock: already-worked shifts cannot be changed or removed.
    if (com.nordicframtiden.pharmacy.ShiftLockPolicy.isLocked(s.getStartAt(), today())) {
      throw new com.nordicframtiden.pharmacy.ShiftLockedException(
          com.nordicframtiden.pharmacy.ShiftLockPolicy.lockedMessage(s.getStartAt()));
    }
    // Moving a shift into the past is a history edit too.
    if (com.nordicframtiden.pharmacy.ShiftLockPolicy.isLocked(startAt, today())) {
      throw new com.nordicframtiden.pharmacy.ShiftLockedException(
          com.nordicframtiden.pharmacy.ShiftLockPolicy.pastCreationMessage(startAt));
    }

    if (deletionPolicy != null && (userId != null || startAt != null)) {
      deletionPolicy.assertShiftsAllowed(
          userId != null ? userId : s.getUser().getId(),
          startAt != null ? startAt : s.getStartAt());
    }

    if (userId != null) {
      var user = userRepo.findById(userId)
          .orElseThrow(() -> new IllegalArgumentException("User not found"));
      s.setUser(user);
    }
    if (startAt != null)
      s.setStartAt(startAt);
    if (endAt != null)
      s.setEndAt(endAt);
    if (startAt != null || endAt != null) {
      if (!s.getStartAt().isBefore(s.getEndAt())) {
        throw new IllegalArgumentException("startAt must be before endAt");
      }
    }
    s.setNote(note);

    return repo.save(s);
  }

  @Transactional
  public void delete(Long id) {
    // Past-shift lock: already-worked shifts cannot be removed.
    var s = repo.findById(id).orElseThrow(() -> new IllegalArgumentException("Shift not found"));
    if (com.nordicframtiden.pharmacy.ShiftLockPolicy.isLocked(s.getStartAt(), today())) {
      throw new com.nordicframtiden.pharmacy.ShiftLockedException(
          com.nordicframtiden.pharmacy.ShiftLockPolicy.lockedMessage(s.getStartAt()));
    }
    repo.deleteById(id);
  }
}