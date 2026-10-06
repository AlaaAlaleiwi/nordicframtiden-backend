package com.nordicframtiden.availability;

import com.nordicframtiden.api.AvailabilityController;
import com.nordicframtiden.security.repo.AppUserRepository;
import com.nordicframtiden.security.repo.UserProfileRepository;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

@Service
public class AvailabilityService {

  private final AvailabilityRequestRepository repo;
  private final AppUserRepository userRepo;
  private final UserProfileRepository profileRepo;


  public AvailabilityService(
      AvailabilityRequestRepository repo,
      AppUserRepository userRepo,
      UserProfileRepository profileRepo
  ) {
    this.repo = repo;
    this.userRepo = userRepo;
    this.profileRepo = profileRepo;
  }

  public List<AvailabilityRequest> my(Authentication auth) {
    var user = userRepo.findByUsername(auth.getName())
        .orElseThrow(() -> new IllegalArgumentException("User not found"));
    return repo.findByUserIdOrderByCreatedAtDesc(user.getId());
  }

  @Transactional(readOnly = true)
  public List<AvailabilityRequestRepository.AvailabilityRequestSummary> mySummaries(Authentication auth) {
    var user = userRepo.findByUsername(auth.getName())
        .orElseThrow(() -> new IllegalArgumentException("User not found"));
    return repo.findSummariesByUserIdOrderByCreatedAtDesc(user.getId());
  }

  @Transactional(readOnly = true)
  public List<AvailabilityRequestRepository.AvailabilityRequestSummary> allSummaries() {
    return repo.findAllSummariesOrderByCreatedAtDesc();
  }

  @Transactional(readOnly = true)
  public AvailabilityRequestRepository.AvailabilityRequestSummary summaryById(Long id) {
    return repo.findSummaryById(id)
        .orElseThrow(() -> new IllegalArgumentException("Request not found"));
  }

  @Transactional
  public AvailabilityRequest createForMe(Authentication auth,
                                        AvailabilityRequest.Type type,
                                        LocalDate start,
                                        LocalDate end,
                                        LocalTime startTime,
                                        LocalTime endTime,
                                        String note) {

    var user = userRepo.findByUsername(auth.getName())
        .orElseThrow(() -> new IllegalArgumentException("User not found"));

    if (start == null || end == null) throw new IllegalArgumentException("start/end required");
    if (end.isBefore(start)) throw new IllegalArgumentException("end must be >= start");
    if (start.plusDays(62).isBefore(end)) throw new IllegalArgumentException("range too large");
    if (startTime != null && endTime != null && !endTime.isAfter(startTime)) {
      throw new IllegalArgumentException("end time must be after start time");
    }

    var r = new AvailabilityRequest();
    r.setUser(user);
    r.setType(type);
    r.setStartDate(start);
    r.setEndDate(end);
    r.setStartTime(startTime);
    r.setEndTime(endTime);
    r.setNote(note == null ? null : note.trim());

    // ✅ default status if your entity doesn’t do it
    if (r.getStatus() == null) r.setStatus(AvailabilityRequest.Status.PENDING);

    return repo.save(r);
  }

  public List<AvailabilityRequest> all() {
    return repo.findAllOrderByCreatedAtDesc();
  }

  @Transactional
  public AvailabilityRequest setStatus(Long id, AvailabilityRequest.Status status) {
    var r = repo.findById(id).orElseThrow(() -> new IllegalArgumentException("Request not found"));
    r.setStatus(status);
    return repo.save(r);
  }

  @Transactional(readOnly = true)
  public List<AvailabilityRequestRepository.AvailabilityRequestSummary> getOverlappingSummaries(LocalDate start, LocalDate end, String statusesCsv) {
    List<AvailabilityRequest.Status> statuses = parseStatuses(statusesCsv);
    if (statuses != null && statuses.isEmpty()) return List.of();
    return statuses == null
        ? repo.findApprovedOverlappingSummaries(start, end)
        : repo.findByStatusInAndOverlappingSummaries(statuses, start, end);
  }

  @Transactional(readOnly = true)
  public List<AvailabilityController.AvailabilityRow> getApprovedOverlapping(LocalDate start, LocalDate end) {
    return getOverlapping(start, end, null);
  }

  /**
   * Availability rows overlapping [start, end]. statusesCsv is a comma-separated
   * list (e.g. "PENDING,APPROVED"); null/blank falls back to APPROVED only, so
   * existing callers keep their semantics.
   */
  @Transactional(readOnly = true)
  public List<AvailabilityController.AvailabilityRow> getOverlapping(LocalDate start, LocalDate end, String statusesCsv) {
    List<AvailabilityRequest.Status> statuses = parseStatuses(statusesCsv);
    if (statuses != null && statuses.isEmpty()) return List.of(); // no valid statuses -> nothing can match
    var rows = (statuses == null)
        ? repo.findApprovedOverlapping(start, end)
        : repo.findByStatusInAndOverlapping(statuses, start, end);

    // Defensive re-filter: the guarantee "only the requested statuses come
    // back" lives here too, not only in the SQL.
    return rows.stream()
        .filter(a -> statuses == null
            ? a.getStatus() == AvailabilityRequest.Status.APPROVED
            : statuses.contains(a.getStatus()))
        .map(a -> {
      var u = a.getUser();                 // ✅ use relation
      var userId = u.getId();

      var p = profileRepo.findByUserId(userId).orElse(null);

      String username = u.getUsername();
      String fullName = (p != null ? p.getFullName() : null);

      return new AvailabilityController.AvailabilityRow(
          a.getId(),
          userId,
          username,
          fullName,
          a.getType().name(),
          a.getStartDate().toString(),
          a.getEndDate().toString(),
          a.getStartTime() == null ? null : a.getStartTime().toString(),
          a.getEndTime() == null ? null : a.getEndTime().toString(),
          a.getStatus().name(),
          a.getNote()
      );
    }).toList();
  }

  private static List<AvailabilityRequest.Status> parseStatuses(String statusesCsv) {
    if (statusesCsv == null || statusesCsv.isBlank()) return null;
    return java.util.Arrays.stream(statusesCsv.split(","))
        .map(String::trim)
        .filter(s -> !s.isEmpty())
        .map(s -> {
          try {
            return AvailabilityRequest.Status.valueOf(s.toUpperCase(java.util.Locale.ROOT));
          } catch (IllegalArgumentException e) {
            return null;
          }
        })
        .filter(java.util.Objects::nonNull)
        .toList();
  }
}