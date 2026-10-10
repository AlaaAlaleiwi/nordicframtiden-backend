package com.nordicframtiden.gdpr;

import com.nordicframtiden.availability.AvailabilityRequest;
import com.nordicframtiden.availability.AvailabilityRequestRepository;
import com.nordicframtiden.chat.CallHistoryRepository;
import com.nordicframtiden.chat.ChatAttachmentRepository;
import com.nordicframtiden.chat.ChatMessage;
import com.nordicframtiden.chat.ChatMessageRepository;
import com.nordicframtiden.chat.ChatPushSubscriptionRepository;
import com.nordicframtiden.chat.ChatReactionRepository;
import com.nordicframtiden.chat.ChatRoom;
import com.nordicframtiden.chat.ChatRoomMemberRepository;
import com.nordicframtiden.chat.ChatRoomRepository;
import com.nordicframtiden.company.StaffShift;
import com.nordicframtiden.company.StaffShiftRepository;
import com.nordicframtiden.documents.ProfileDocument;
import com.nordicframtiden.documents.ProfileDocumentRepository;
import com.nordicframtiden.pharmacy.ScheduleShift;
import com.nordicframtiden.pharmacy.ScheduleShiftRepository;
import com.nordicframtiden.security.model.AppUser;
import com.nordicframtiden.security.model.UserProfile;
import com.nordicframtiden.security.repo.AppUserRepository;
import com.nordicframtiden.security.repo.PasswordResetTokenRepository;
import com.nordicframtiden.security.repo.UserProfileRepository;
import com.nordicframtiden.security.service.UserService;
import com.nordicframtiden.service.model.PayslipSnapshotRepository;
import com.nordicframtiden.service.model.SalaryAdjustment;
import com.nordicframtiden.service.model.SalaryAdjustmentRepository;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * GDPR self-service core:
 * - Art. 15/20: machine-readable export of every data category we hold for
 *   the account (profile, shifts, availability, salary adjustments, payslip
 *   snapshots, chat messages, call history, documents metadata).
 * - Art. 17: full erasure through the existing UserService deletion path —
 *   with the footprint gaps it had closed first (memberships, reactions,
 *   push subscriptions, reset tokens, documents).
 * - Art. 7: consent ledger recording grants/withdrawals and the erasure and
 *   access request markers themselves.
 */
@Service
public class GdprService {

  /** Machine-readable data export (JSON), grouped by category (Art. 20 portability + Art. 15 access). */
  public record GdprExport(
      String format,
      String generatedAt,
      long userId,
      String username,
      Map<String, Object> profile,
      Map<String, Object> consents,
      List<Map<String, Object>> shifts,
      List<Map<String, Object>> staffShifts,
      List<Map<String, Object>> availabilityRequests,
      List<Map<String, Object>> salaryAdjustments,
      List<Map<String, Object>> payslipSnapshots,
      List<Map<String, Object>> chatMessages,
      List<Map<String, Object>> callHistory,
      List<Map<String, Object>> documents
  ) {}

  /**
   * "All time" bounds for the shift range queries. OffsetDateTime.MIN/MAX are
   * outside what timestamptz (and the driver's UTC conversion) can represent,
   * so use wide-but-valid UTC instants instead.
   */
  static final OffsetDateTime EXPORT_RANGE_START = OffsetDateTime.of(1900, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);
  static final OffsetDateTime EXPORT_RANGE_END = OffsetDateTime.of(9999, 12, 31, 23, 59, 59, 0, ZoneOffset.UTC);

  private final AppUserRepository userRepo;
  private final UserProfileRepository profileRepo;
  private final GdprConsentRepository consentRepo;
  private final ScheduleShiftRepository scheduleShifts;
  private final StaffShiftRepository staffShifts;
  private final AvailabilityRequestRepository availabilityRequests;
  private final SalaryAdjustmentRepository salaryAdjustments;
  private final PayslipSnapshotRepository payslipSnapshots;
  private final ChatMessageRepository chatMessages;
  private final ChatRoomRepository chatRooms;
  private final ChatRoomMemberRepository chatRoomMembers;
  private final CallHistoryRepository callHistory;
  private final ProfileDocumentRepository profileDocuments;
  private final GdprExportRequestRepository exportRequests;
  private final UserService userService;

  public GdprService(
      AppUserRepository userRepo,
      UserProfileRepository profileRepo,
      GdprConsentRepository consentRepo,
      ScheduleShiftRepository scheduleShifts,
      StaffShiftRepository staffShifts,
      AvailabilityRequestRepository availabilityRequests,
      SalaryAdjustmentRepository salaryAdjustments,
      PayslipSnapshotRepository payslipSnapshots,
      ChatMessageRepository chatMessages,
      ChatRoomRepository chatRooms,
      ChatRoomMemberRepository chatRoomMembers,
      CallHistoryRepository callHistory,
      ProfileDocumentRepository profileDocuments,
      GdprExportRequestRepository exportRequests,
      UserService userService
  ) {
    this.userRepo = userRepo;
    this.profileRepo = profileRepo;
    this.consentRepo = consentRepo;
    this.scheduleShifts = scheduleShifts;
    this.staffShifts = staffShifts;
    this.availabilityRequests = availabilityRequests;
    this.salaryAdjustments = salaryAdjustments;
    this.payslipSnapshots = payslipSnapshots;
    this.chatMessages = chatMessages;
    this.chatRooms = chatRooms;
    this.chatRoomMembers = chatRoomMembers;
    this.callHistory = callHistory;
    this.profileDocuments = profileDocuments;
    this.exportRequests = exportRequests;
    this.userService = userService;
  }

  // ---------- Art. 7: consent ledger ----------

  public GdprConsent recordConsent(Long userId, String username, String type, boolean granted) {
    GdprConsent saved = consentRepo.save(new GdprConsent(userId, username, type, granted));
    if (GdprConsent.TYPE_ACCESS_REQUEST.equals(type) && granted) {
      // Marking that the data subject exercised their access right.
    }
    return saved;
  }

  /** Current consent state per type (latest event wins). */
  public Map<String, Boolean> currentConsents(Long userId) {
    Map<String, Boolean> state = new LinkedHashMap<>();
    for (String type : List.of(
        GdprConsent.TYPE_TERMS,
        GdprConsent.TYPE_DATA_PROCESSING,
        GdprConsent.TYPE_EMAIL_NOTIFICATIONS,
        GdprConsent.TYPE_PUSH_NOTIFICATIONS)) {
      consentRepo.findTopByUserIdAndConsentTypeOrderByCreatedAtDesc(userId, type)
          .ifPresent(c -> state.put(type, c.isGranted()));
    }
    return state;
  }

  public List<GdprConsent> consentHistory(Long userId) {
    return consentRepo.findByUserIdOrderByCreatedAtDesc(userId);
  }

  // ---------- Art. 15/20: email delivery of the export ----------

  /**
   * Queues an email delivery of the data export. The user has confirmed the
   * request in the client (24h promise); the 03:00 job emails the JSON.
   * Idempotent: while a PENDING request exists for the user, re-requesting
   * returns it unchanged instead of queueing duplicates.
   */
  @Transactional
  public GdprExportRequest requestExportByEmail(Long userId) {
    AppUser user = userRepo.findById(userId)
        .orElseThrow(() -> new IllegalArgumentException("User not found"));
    UserProfile profile = profileRepo.findByUserId(userId)
        .orElseThrow(() -> new IllegalArgumentException("No profile on file"));
    String email = profile.getEmail();
    if (email == null || email.isBlank()) {
      throw new IllegalArgumentException("No email address on file");
    }
    return exportRequests.findByStatusOrderByCreatedAtAsc(GdprExportRequest.STATUS_PENDING).stream()
        .filter(r -> r.getUserId().equals(userId))
        .findFirst()
        .orElseGet(() -> {
          // The data subject exercised their access right (audit marker).
          recordConsent(userId, user.getUsername(), GdprConsent.TYPE_ACCESS_REQUEST, true);
          return exportRequests.save(new GdprExportRequest(userId, user.getUsername(), email.trim()));
        });
  }

  /** Latest export request (for status display), or empty when none exists. */
  @Transactional(readOnly = true)
  public Optional<GdprExportRequest> latestExportRequest(Long userId) {
    List<GdprExportRequest> all = exportRequests.findTop20ByUserIdOrderByCreatedAtDesc(userId);
    return all.isEmpty() ? Optional.empty() : Optional.of(all.get(0));
  }

  // ---------- Art. 15/20: data export ----------

  @Transactional(readOnly = true)
  public GdprExport export(Long userId) {
    AppUser user = userRepo.findById(userId)
        .orElseThrow(() -> new IllegalArgumentException("User not found"));
    UserProfile profile = profileRepo.findByUserId(userId).orElse(null);

    Map<String, Object> profileMap = new LinkedHashMap<>();
    profileMap.put("username", user.getUsername());
    profileMap.put("enabled", user.isEnabled());
    profileMap.put("roles", user.getRoles().stream().map(Enum::name).toList());
    if (profile != null) {
      profileMap.put("fullName", profile.getFullName());
      profileMap.put("email", profile.getEmail());
      profileMap.put("phone", profile.getPhone());
      profileMap.put("hourlyCost", profile.getHourlyCost());
      profileMap.put("yearOfBirth", profile.getYearOfBirth());
      profileMap.put("countyCode", profile.getCountyCode());
      profileMap.put("municipalityCode", profile.getMunicipalityCode());
    }

    // Every message the account sent, queried by sender: covers rooms the user
    // has since left and thread replies, with no per-room cap.
    List<Map<String, Object>> messages = new ArrayList<>();
    for (ChatMessage m : chatMessages.findAllBySenderIdForExport(userId)) {
      ChatRoom room = m.getRoom();
      Map<String, Object> message = new LinkedHashMap<>();
      message.put("messageId", m.getId());
      message.put("roomId", room == null ? null : room.getId());
      message.put("roomName", room == null || room.getName() == null ? "" : room.getName());
      message.put("parentMessageId", m.getParent() == null ? null : m.getParent().getId());
      message.put("body", m.getBody());
      message.put("createdAt", m.getCreatedAt() == null ? "" : m.getCreatedAt().toString());
      message.put("editedAt", m.getEditedAt() == null ? "" : m.getEditedAt().toString());
      message.put("deletedAt", m.getDeletedAt() == null ? "" : m.getDeletedAt().toString());
      messages.add(message);
    }

    List<Map<String, Object>> documents = profileDocuments.findByUserIdOrderByIdDesc(userId).stream()
        .map(d -> {
          Map<String, Object> map = new LinkedHashMap<>();
          map.put("id", d.getId());
          map.put("fileName", d.getFileName());
          map.put("contentType", d.getContentType());
          map.put("sizeBytes", d.getSizeBytes());
          map.put("createdAt", d.getCreatedAt() == null ? "" : d.getCreatedAt().toString());
          return map;
        })
        .toList();

    List<Map<String, Object>> snapshots = payslipSnapshots.findByUserId(userId).stream()
        .map(s -> {
          Map<String, Object> map = new LinkedHashMap<>();
          map.put("year", s.getYear());
          map.put("month", s.getMonth());
          map.put("role", s.getRole());
          map.put("payload", s.getPayload());
          map.put("revisions", List.copyOf(s.getRevisions()));
          return map;
        })
        .toList();

    return new GdprExport(
        "json",
        Instant.now().toString(),
        userId,
        user.getUsername(),
        profileMap,
        new LinkedHashMap<>(currentConsents(userId)),
        scheduleShifts.findInRange(EXPORT_RANGE_START, EXPORT_RANGE_END, null, userId).stream()
            .map(this::shiftMap)
            .toList(),
        staffShifts.findInRange(EXPORT_RANGE_START, EXPORT_RANGE_END, userId).stream()
            .map(this::staffShiftMap)
            .toList(),
        availabilityRequests.findByUserIdOrderByCreatedAtDesc(userId).stream()
            .map(this::availabilityMap)
            .toList(),
        salaryAdjustments.findByUserId(userId).stream()
            .map(this::adjustmentMap)
            .toList(),
        snapshots,
        messages,
        callHistory.findByCaller(user).stream()
            .map(c -> {
              Map<String, Object> map = new LinkedHashMap<>();
              map.put("callId", c.getCallId() == null ? "" : c.getCallId().toString());
              map.put("startedAt", c.getStartedAt() == null ? "" : c.getStartedAt().toString());
              map.put("answeredAt", c.getAnsweredAt() == null ? "" : c.getAnsweredAt().toString());
              map.put("endedAt", c.getEndedAt() == null ? "" : c.getEndedAt().toString());
              map.put("outcome", c.getOutcome());
              map.put("video", c.isVideo());
              return map;
            })
            .toList(),
        documents
    );
  }

  private Map<String, Object> shiftMap(ScheduleShift s) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("id", s.getId());
    map.put("startAt", s.getStartAt() == null ? "" : s.getStartAt().toString());
    map.put("endAt", s.getEndAt() == null ? "" : s.getEndAt().toString());
    map.put("note", s.getNote() == null ? "" : s.getNote());
    return map;
  }

  private Map<String, Object> staffShiftMap(StaffShift s) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("id", s.getId());
    map.put("startAt", s.getStartAt() == null ? "" : s.getStartAt().toString());
    map.put("endAt", s.getEndAt() == null ? "" : s.getEndAt().toString());
    return map;
  }

  private Map<String, Object> availabilityMap(AvailabilityRequest r) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("id", r.getId());
    map.put("type", r.getType() == null ? "" : r.getType().name());
    map.put("startDate", r.getStartDate() == null ? "" : r.getStartDate().toString());
    map.put("endDate", r.getEndDate() == null ? "" : r.getEndDate().toString());
    map.put("status", r.getStatus() == null ? "" : r.getStatus().name());
    map.put("startTime", r.getStartTime() == null ? "" : r.getStartTime().toString());
    map.put("endTime", r.getEndTime() == null ? "" : r.getEndTime().toString());
    map.put("note", r.getNote() == null ? "" : r.getNote());
    map.put("createdAt", r.getCreatedAt() == null ? "" : r.getCreatedAt().toString());
    return map;
  }

  private Map<String, Object> adjustmentMap(SalaryAdjustment a) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("year", a.getYear());
    map.put("month", a.getMonth());
    map.put("name", a.getName());
    map.put("amount", a.getAmount());
    map.put("taxTreatment", a.getTaxTreatment() == null ? "" : a.getTaxTreatment().name());
    map.put("reimbursementType", a.getReimbursementType() == null ? "" : a.getReimbursementType().name());
    map.put("quantity", a.getQuantity());
    return map;
  }
}
