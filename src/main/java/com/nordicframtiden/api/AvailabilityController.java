package com.nordicframtiden.api;

import com.nordicframtiden.availability.AvailabilityRequest;
import com.nordicframtiden.availability.AvailabilityService;
import com.nordicframtiden.availability.AvailabilityRequestRepository.AvailabilityRequestSummary;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

@RestController
@RequestMapping("/api/availability")
@PreAuthorize("hasAnyRole('ADMIN', 'STAFF','USER')")
public class AvailabilityController {

  private final AvailabilityService service;

  public AvailabilityController(AvailabilityService service) {
    this.service = service;
  }

  public record CreateReq(
      String type, // "DAY" | "WEEK" | "RANGE"
      LocalDate startDate,
      LocalDate endDate,
      LocalTime startTime, // optional daily from–to window
      LocalTime endTime,
      String note
  ) {}

  public record AvailabilityDto(
      Long id,
      Long userId,
      String userFullName,
      String username,
      String type,
      LocalDate startDate,
      LocalDate endDate,
      LocalTime startTime,
      LocalTime endTime,
      String status,
      String note
  ) {}

  public record StatusUpdateRequest(AvailabilityRequest.Status status) {}

  public record AvailabilityRow(
      Long id,
      Long userId,
      String username,
      String userFullName,
      String type,
      String startDate,
      String endDate,
      String startTime,
      String endTime,
      String status,
      String note
  ) {}

  private AvailabilityDto toDto(AvailabilityRequestSummary request) {
    return new AvailabilityDto(
        request.getId(), request.getUserId(), request.getUserFullName(), request.getUsername(),
        request.getType().name(), request.getStartDate(), request.getEndDate(), request.getStartTime(),
        request.getEndTime(), request.getStatus().name(), request.getNote());
  }

  private AvailabilityRow toRow(AvailabilityRequestSummary request) {
    return new AvailabilityRow(
        request.getId(), request.getUserId(), request.getUsername(), request.getUserFullName(),
        request.getType().name(), request.getStartDate().toString(), request.getEndDate().toString(),
        request.getStartTime() == null ? null : request.getStartTime().toString(),
        request.getEndTime() == null ? null : request.getEndTime().toString(),
        request.getStatus().name(), request.getNote());
  }

  @GetMapping("/me")
  public List<AvailabilityDto> my(Authentication auth) {
    return service.mySummaries(auth).stream().map(this::toDto).toList();
  }

  @PostMapping("/me")
 
  public AvailabilityDto create(Authentication auth, @RequestBody CreateReq req) {
    var type = AvailabilityRequest.Type.valueOf(req.type());
    var created = service.createForMe(auth, type, req.startDate(), req.endDate(), req.startTime(), req.endTime(), req.note());
    return toDto(service.summaryById(created.getId()));
  }

  @GetMapping
  @PreAuthorize("hasRole('ADMIN') or hasAuthority('PERM_AVAILABILITY')")
 
  public List<AvailabilityDto> all() {
    return service.allSummaries().stream().map(this::toDto).toList();
  }

  @PatchMapping("/{id}/status")
  @PreAuthorize("hasRole('ADMIN') or hasAuthority('PERM_AVAILABILITY')")
 
  public void updateStatus(@PathVariable Long id, @RequestBody StatusUpdateRequest req) {
    service.setStatus(id, req.status());   // ✅ enum type matches
  }

  @GetMapping("/range")
  @PreAuthorize("hasRole('ADMIN') or hasAuthority('PERM_AVAILABILITY')")
 
  public List<AvailabilityRow> range(@RequestParam LocalDate start, @RequestParam LocalDate end,
      @RequestParam(required = false) String statuses) {
    return service.getOverlappingSummaries(start, end, statuses).stream().map(this::toRow).toList();
  }
}