package com.nordicframtiden.api;

import com.nordicframtiden.pharmacy.ScheduleService;
import com.nordicframtiden.pharmacy.ScheduleShift;
import com.nordicframtiden.pharmacy.ScheduleShiftRepository;
import com.nordicframtiden.notification.PushNotificationService;
import com.nordicframtiden.security.repo.UserProfileRepository;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/schedules")
@PreAuthorize("hasAnyRole('ADMIN','STAFF','USER')")
public class ScheduleController {

  private final ScheduleService service;
  private final UserProfileRepository userProfileRepository;
  private final ScheduleShiftRepository shiftRepository;
  private final PushNotificationService notifications;

  public ScheduleController(ScheduleService service, UserProfileRepository userProfileRepository,
      ScheduleShiftRepository shiftRepository, PushNotificationService notifications) {
    this.service = service;
    this.userProfileRepository = userProfileRepository;
    this.shiftRepository = shiftRepository;
    this.notifications = notifications;
  }

  public record EventDto(
      Long id,
      Long pharmacyId,
      String pharmacyName,
      Long userId,
      String userLabel,
      OffsetDateTime startAt,
      OffsetDateTime endAt,
      String note) {
    static EventDto from(ScheduleShift s, String username) {
      Long pid = s.getPharmacy() == null ? null : s.getPharmacy().getId();
      String pname = s.getPharmacy() == null ? null : s.getPharmacy().getName();

      return new EventDto(
          s.getId(),
          pid,
          pname,
          s.getUser().getId(),
          username,
          s.getStartAt(),
          s.getEndAt(),
          s.getNote());
    }
  }

  // ✅ pharmacist gets own schedule
  @GetMapping("/me")
  public org.springframework.http.ResponseEntity<List<EventDto>> mySchedules(@RequestParam OffsetDateTime start,
      @RequestParam OffsetDateTime end,
      Authentication auth) {

    var schedules = service.listForCurrentUser(auth, start, end);
    var profilesByUserId = loadProfilesByUserId(schedules);
    var res = schedules.stream()
        .map(s -> toEventDto(s, profilesByUserId))
        .toList();

    return org.springframework.http.ResponseEntity.ok()
        .cacheControl(org.springframework.http.CacheControl.maxAge(java.time.Duration.ofMinutes(5)).cachePrivate())
        .body(res);
  }

  // ✅ admin listing
  @GetMapping
  public List<EventDto> list(@RequestParam OffsetDateTime start,
      @RequestParam OffsetDateTime end,
      @RequestParam(required = false) Long pharmacyId,
      @RequestParam(required = false) Long userId) {
    var schedules = service.listRange(start, end, pharmacyId, userId);
    var profilesByUserId = loadProfilesByUserId(schedules);
    return schedules.stream()
        .map(s -> toEventDto(s, profilesByUserId))
        .toList();
  }

  private Map<Long, UserProfileRepository.UserProfileSummary> loadProfilesByUserId(
      List<ScheduleShift> schedules) {
    var userIds = schedules.stream()
        .map(schedule -> schedule.getUser().getId())
        .distinct()
        .toList();
    if (userIds.isEmpty()) return Map.of();
    return userProfileRepository.findSummariesByUserIdIn(userIds).stream()
        .collect(Collectors.toMap(UserProfileRepository.UserProfileSummary::getUserId, profile -> profile));
  }

  private EventDto toEventDto(
      ScheduleShift schedule,
      Map<Long, UserProfileRepository.UserProfileSummary> profilesByUserId) {
    Long pharmacyId = schedule.getPharmacy() == null ? null : schedule.getPharmacy().getId();
    String pharmacyName = schedule.getPharmacy() == null ? null : schedule.getPharmacy().getName();
    Long userId = schedule.getUser().getId();
    var profile = profilesByUserId.get(userId);
    String userLabel = profile != null ? profile.getFullName() : schedule.getUser().getUsername();
    return new EventDto(schedule.getId(), pharmacyId, pharmacyName, userId, userLabel,
        schedule.getStartAt(), schedule.getEndAt(), schedule.getNote());
  }

  public record CreateRequest(
      Long pharmacyId,
      Long userId,
      OffsetDateTime startAt,
      OffsetDateTime endAt,
      String note) {
  }

  // ✅ THIS is what your UI is calling
  @PostMapping
  public EventDto create(@RequestBody CreateRequest req) {
    var created = service.create(
        req.pharmacyId(),
        req.userId(),
        req.startAt(),
        req.endAt(),
        req.note());

    notifications.notifyUser(created.getUser().getId(), "schedule.added",
        "New schedule", "A new shift was added to your schedule.",
        java.util.Map.of("scheduleId", created.getId()));

    Long pid = created.getPharmacy() == null ? null : created.getPharmacy().getId();
    String pname = created.getPharmacy() == null ? null : created.getPharmacy().getName();
    var u = userProfileRepository.findByUserId(created.getUser().getId());
    return new EventDto(
        created.getId(),
        pid,
        pname,
        created.getUser().getId(),
        u.get().getFullName(),
        created.getStartAt(),
        created.getEndAt(),
        created.getNote());

  }

  @PutMapping("/{id}")
  public EventDto update(@PathVariable Long id,
      @RequestBody CreateRequest req) {

    Long previousUserId = shiftRepository.findById(id)
        .map(shift -> shift.getUser().getId()).orElse(null);

    var updated = service.update(
        id,
        req.pharmacyId(),
        req.userId(),
        req.startAt(),
        req.endAt(),
        req.note());

    Long updatedUserId = updated.getUser().getId();
    notifications.notifyUser(updatedUserId, "schedule.updated",
        "Schedule updated", "Your schedule has been changed.",
        java.util.Map.of("scheduleId", updated.getId()));
    if (previousUserId != null && !previousUserId.equals(updatedUserId)) {
      notifications.notifyUser(previousUserId, "schedule.deleted",
          "Schedule updated", "A shift was removed from your schedule.",
          java.util.Map.of("scheduleId", updated.getId()));
    }

    Long pid = updated.getPharmacy() == null ? null : updated.getPharmacy().getId();
    String pname = updated.getPharmacy() == null ? null : updated.getPharmacy().getName();
    var u = userProfileRepository.findByUserId(updated.getUser().getId());

    return new EventDto(
        updated.getId(),
        pid,
        pname,
        updated.getUser().getId(),
        u.get().getFullName(),
        updated.getStartAt(),
        updated.getEndAt(),
        updated.getNote());
  }

  @DeleteMapping("/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete(@PathVariable Long id) {
    Long userId = shiftRepository.findById(id)
        .map(shift -> shift.getUser().getId()).orElse(null);
    service.delete(id);
    if (userId != null) {
      notifications.notifyUser(userId, "schedule.deleted",
          "Schedule updated", "A shift was removed from your schedule.",
          java.util.Map.of("scheduleId", id));
    }
  }
}
