package com.nordicframtiden.api;

import com.nordicframtiden.company.StaffScheduleService;
import com.nordicframtiden.company.StaffShift;
import com.nordicframtiden.security.repo.UserProfileRepository;
import com.nordicframtiden.settings.EmailService;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication; // ✅ correct
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/staff-schedules")
@PreAuthorize("hasAnyRole('ADMIN','STAFF')") // ✅ staff only
public class StaffScheduleController {

  private final StaffScheduleService service;
  private final EmailService emailService;
  private final UserProfileRepository profileRepo;

  public StaffScheduleController(StaffScheduleService service, EmailService emailService,
      UserProfileRepository profileRepo) {
    this.service = service;
    this.emailService = emailService;
    this.profileRepo = profileRepo;
  }

  public record EventDto(
      Long id,
      Long userId,
      String userLabel,
      OffsetDateTime startAt,
      OffsetDateTime endAt,
      String note
  ) {
    static EventDto from(StaffShift s) {
      return new EventDto(
          s.getId(),
          s.getUser().getId(),
          "@" + s.getUser().getUsername(),
          s.getStartAt(),
          s.getEndAt(),
          s.getNote()
      );
    }
  }

  /** ✅ staff calls this */
  @GetMapping("/me")
  public List<EventDto> myStaffSchedules(
      @RequestParam OffsetDateTime start,
      @RequestParam OffsetDateTime end,
      Authentication auth
  ) {
    return service.listForCurrentUser(auth, start, end)
        .stream()
        .map(EventDto::from)
        .toList();
  }

  /** ✅ admin listing */
  @GetMapping
 
  public List<EventDto> list(
      @RequestParam OffsetDateTime start,
      @RequestParam OffsetDateTime end,
      @RequestParam(required = false) Long userId
  ) {
    return service.listRange(start, end, userId).stream().map(EventDto::from).toList();
  }

  public record CreateRequest(
      @NotNull Long userId,
      @NotNull OffsetDateTime startAt,
      @NotNull OffsetDateTime endAt,
      String note
  ) {}

  public record UpdateRequest(
      Long userId,
      OffsetDateTime startAt,
      OffsetDateTime endAt,
      String note
  ) {}

  @PostMapping
   
  public EventDto create(@RequestBody CreateRequest req) {
    var created = service.create(req.userId(), req.startAt(), req.endAt(), req.note());
    return EventDto.from(created);
  }

  @PutMapping("/{id}")
  
  public EventDto update(@PathVariable Long id, @RequestBody UpdateRequest req) {
    var updated = service.update(id, req.userId(), req.startAt(), req.endAt(), req.note());
    return EventDto.from(updated);
  }

  @DeleteMapping("/{id}")
   
  public void delete(@PathVariable Long id) {
    service.delete(id);
  }

  @PostMapping("/send-pdf-email")
  public ResponseEntity<Map<String, Object>> sendSchedulePdfEmail(@RequestBody Map<String, Object> payload) {
    Long userId = payload.get("userId") instanceof Number n ? n.longValue() : null;
    String pdfBase64 = payload.get("pdfBase64") == null ? "" : String.valueOf(payload.get("pdfBase64")).trim();
    String startIso = payload.get("startDate") == null ? null : String.valueOf(payload.get("startDate")).trim();
    String endIso = payload.get("endDate") == null ? null : String.valueOf(payload.get("endDate")).trim();

    if (userId == null || pdfBase64.isBlank()) {
      return ResponseEntity.status(HttpStatus.BAD_REQUEST)
          .body(Map.of("error", "Missing required fields to send the schedule PDF email."));
    }

    // The recipient is the employee's stored email, never a caller-supplied
    // address: the company sender must not mail arbitrary files anywhere.
    var profile = profileRepo.findByUserId(userId).orElse(null);
    String email = profile == null || profile.getEmail() == null ? "" : profile.getEmail().trim();
    if (email.isBlank() || !email.contains("@")) {
      return ResponseEntity.status(HttpStatus.BAD_REQUEST)
          .body(Map.of("error", "The employee does not have a valid profile email."));
    }
    String employeeName = profile.getFullName() == null ? "" : profile.getFullName().trim();

    byte[] pdfBytes;
    try {
      pdfBytes = Base64.getDecoder().decode(pdfBase64);
    } catch (IllegalArgumentException e) {
      return ResponseEntity.status(HttpStatus.BAD_REQUEST)
          .body(Map.of("error", "Invalid PDF payload."));
    }

    OffsetDateTime start = startIso == null || startIso.isBlank() ? null : OffsetDateTime.parse(startIso);
    OffsetDateTime end = endIso == null || endIso.isBlank() ? null : OffsetDateTime.parse(endIso);

    boolean sent = emailService.sendSchedulePdfEmail(
        email,
        employeeName.isBlank() ? "Staff member" : employeeName,
        pdfBytes,
        "Staff schedule",
        start,
        end
    );

    // Map.of rejects null values, and the dates are optional: a missing date
    // must come back as JSON null, not a 500.
    Map<String, Object> body = new java.util.LinkedHashMap<>();
    body.put("sent", sent);
    body.put("recipient", email);
    body.put("employeeName", employeeName.isBlank() ? "Staff member" : employeeName);
    body.put("startDate", startIso);
    body.put("endDate", endIso);
    body.put("filename", "schedule-" + (startIso == null || startIso.length() < 10 ? "period" : startIso.substring(0, 10)) + ".pdf");
    return ResponseEntity.ok(body);
  }
}