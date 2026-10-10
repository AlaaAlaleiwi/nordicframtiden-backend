package com.nordicframtiden.gdpr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

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
import com.nordicframtiden.security.model.Role;
import com.nordicframtiden.security.model.UserProfile;
import com.nordicframtiden.security.repo.AppUserRepository;
import com.nordicframtiden.security.repo.UserProfileRepository;
import com.nordicframtiden.security.service.UserService;
import com.nordicframtiden.service.model.PayslipSnapshot;
import com.nordicframtiden.service.model.PayslipSnapshotRepository;
import com.nordicframtiden.service.model.SalaryAdjustment;
import com.nordicframtiden.service.model.SalaryAdjustmentRepository;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Art. 15/20 export completeness: the JSON export must cover every personal
 * data category the system stores for the account, and only that account's
 * rows (no leakage from other users' chat messages or adjustments).
 */
@ExtendWith(MockitoExtension.class)
class GdprExportTest {

  @Mock private AppUserRepository userRepo;
  @Mock private UserProfileRepository profileRepo;
  @Mock private GdprConsentRepository consentRepo;
  @Mock private ScheduleShiftRepository scheduleShifts;
  @Mock private StaffShiftRepository staffShifts;
  @Mock private AvailabilityRequestRepository availabilityRequests;
  @Mock private SalaryAdjustmentRepository salaryAdjustments;
  @Mock private PayslipSnapshotRepository payslipSnapshots;
  @Mock private ChatMessageRepository chatMessages;
  @Mock private ChatRoomRepository chatRooms;
  @Mock private ChatRoomMemberRepository chatRoomMembers;
  @Mock private CallHistoryRepository callHistory;
  @Mock private ProfileDocumentRepository profileDocuments;
  @Mock private GdprExportRequestRepository exportRequests;
  @Mock private UserService userService;

  private GdprService service;
  private AppUser user;

  @BeforeEach
  void setUp() {
    service = new GdprService(userRepo, profileRepo, consentRepo, scheduleShifts,
        staffShifts, availabilityRequests, salaryAdjustments, payslipSnapshots,
        chatMessages, chatRooms, chatRoomMembers, callHistory, profileDocuments,
        exportRequests, userService);

    user = new AppUser();
    user.setId(7L);
    user.setUsername("pharm");
    user.setRoles(new java.util.HashSet<>(Set.of(Role.USER)));
  }

  private UserProfile profile() {
    UserProfile p = new UserProfile();
    p.setId(1L);
    p.setUser(user);
    p.setFullName("Anna Andersson");
    p.setEmail("anna@example.com");
    p.setPhone("0701234567");
    p.setYearOfBirth(1990);
    p.setCountyCode("01");
    p.setMunicipalityCode("0114");
    return p;
  }

  @Test
  void export_contains_profile_identity_and_contact_data() {
    when(userRepo.findById(7L)).thenReturn(Optional.of(user));
    when(profileRepo.findByUserId(7L)).thenReturn(Optional.of(profile()));
    when(chatMessages.findAllBySenderIdForExport(7L)).thenReturn(List.of());
    when(availabilityRequests.findByUserIdOrderByCreatedAtDesc(7L)).thenReturn(List.of());
    when(profileDocuments.findByUserIdOrderByIdDesc(7L)).thenReturn(List.of());
    when(scheduleShifts.findInRange(any(), any(), any(), any())).thenReturn(List.of());
    when(staffShifts.findInRange(any(), any(), any())).thenReturn(List.of());
    when(payslipSnapshots.findByUserId(7L)).thenReturn(List.of());
    when(salaryAdjustments.findByUserId(7L)).thenReturn(List.of());

    GdprService.GdprExport export = service.export(7L);

    assertThat(export.profile())
        .containsEntry("username", "pharm")
        .containsEntry("fullName", "Anna Andersson")
        .containsEntry("email", "anna@example.com")
        .containsEntry("phone", "0701234567")
        .containsEntry("yearOfBirth", 1990);
    assertThat(export.format()).isEqualTo("json");
    assertThat(export.userId()).isEqualTo(7L);
  }

  @Test
  void export_includes_only_the_accounts_own_chat_messages() {
    when(userRepo.findById(7L)).thenReturn(Optional.of(user));
    when(profileRepo.findByUserId(7L)).thenReturn(Optional.empty());

    AppUser other = new AppUser();
    other.setId(99L);
    other.setUsername("other");

    ChatRoom room = new ChatRoom();
    room.setId(5L);
    room.setName("Pharmacy chat");

    ChatMessage mine = new ChatMessage();
    mine.setId(1L);
    mine.setRoom(room);
    mine.setSender(user);
    mine.setBody("my message");

    ChatMessage theirs = new ChatMessage();
    theirs.setId(2L);
    theirs.setRoom(room);
    theirs.setSender(other);
    theirs.setBody("someone else's message");

    when(chatMessages.findAllBySenderIdForExport(7L)).thenReturn(List.of(mine));
    when(availabilityRequests.findByUserIdOrderByCreatedAtDesc(7L)).thenReturn(List.of());
    when(profileDocuments.findByUserIdOrderByIdDesc(7L)).thenReturn(List.of());
    when(scheduleShifts.findInRange(any(), any(), any(), any())).thenReturn(List.of());
    when(staffShifts.findInRange(any(), any(), any())).thenReturn(List.of());
    when(payslipSnapshots.findByUserId(7L)).thenReturn(List.of());
    when(salaryAdjustments.findByUserId(7L)).thenReturn(List.of());

    GdprService.GdprExport export = service.export(7L);

    assertThat(export.chatMessages()).hasSize(1);
    assertThat(export.chatMessages().get(0))
        .containsEntry("body", "my message");
  }

  @Test
  void export_includes_messages_from_left_rooms_and_thread_replies() {
    when(userRepo.findById(7L)).thenReturn(Optional.of(user));
    when(profileRepo.findByUserId(7L)).thenReturn(Optional.empty());

    // A room the user has since left: no longer returned by findVisibleTo.
    ChatRoom leftRoom = new ChatRoom();
    leftRoom.setId(11L);
    leftRoom.setName("Old team");
    ChatRoom currentRoom = new ChatRoom();
    currentRoom.setId(12L);
    currentRoom.setName("Current team");

    ChatMessage inLeftRoom = new ChatMessage();
    inLeftRoom.setId(20L);
    inLeftRoom.setRoom(leftRoom);
    inLeftRoom.setSender(user);
    inLeftRoom.setBody("before I left");

    ChatMessage parent = new ChatMessage();
    parent.setId(30L);
    parent.setRoom(currentRoom);

    ChatMessage reply = new ChatMessage();
    reply.setId(31L);
    reply.setRoom(currentRoom);
    reply.setSender(user);
    reply.setParent(parent);
    reply.setBody("thread reply");

    lenient().when(chatRooms.findVisibleTo(7L)).thenReturn(List.of(currentRoom));
    when(chatMessages.findAllBySenderIdForExport(7L)).thenReturn(List.of(inLeftRoom, reply));
    when(availabilityRequests.findByUserIdOrderByCreatedAtDesc(7L)).thenReturn(List.of());
    when(profileDocuments.findByUserIdOrderByIdDesc(7L)).thenReturn(List.of());
    when(scheduleShifts.findInRange(any(), any(), any(), any())).thenReturn(List.of());
    when(staffShifts.findInRange(any(), any(), any())).thenReturn(List.of());
    when(payslipSnapshots.findByUserId(7L)).thenReturn(List.of());
    when(salaryAdjustments.findByUserId(7L)).thenReturn(List.of());

    GdprService.GdprExport export = service.export(7L);

    assertThat(export.chatMessages()).hasSize(2);
    assertThat(export.chatMessages().get(0))
        .containsEntry("roomId", 11L)
        .containsEntry("roomName", "Old team")
        .containsEntry("body", "before I left")
        .containsEntry("parentMessageId", null);
    assertThat(export.chatMessages().get(1))
        .containsEntry("roomId", 12L)
        .containsEntry("body", "thread reply")
        .containsEntry("parentMessageId", 30L);
  }

  @Test
  void export_shift_queries_use_bounds_representable_as_timestamptz() {
    when(userRepo.findById(7L)).thenReturn(Optional.of(user));
    when(profileRepo.findByUserId(7L)).thenReturn(Optional.empty());
    when(chatMessages.findAllBySenderIdForExport(7L)).thenReturn(List.of());
    when(availabilityRequests.findByUserIdOrderByCreatedAtDesc(7L)).thenReturn(List.of());
    when(profileDocuments.findByUserIdOrderByIdDesc(7L)).thenReturn(List.of());
    when(scheduleShifts.findInRange(any(), any(), any(), any())).thenReturn(List.of());
    when(staffShifts.findInRange(any(), any(), any())).thenReturn(List.of());
    when(payslipSnapshots.findByUserId(7L)).thenReturn(List.of());
    when(salaryAdjustments.findByUserId(7L)).thenReturn(List.of());

    service.export(7L);

    var start = org.mockito.ArgumentCaptor.forClass(OffsetDateTime.class);
    var end = org.mockito.ArgumentCaptor.forClass(OffsetDateTime.class);
    org.mockito.Mockito.verify(scheduleShifts).findInRange(start.capture(), end.capture(), any(), org.mockito.ArgumentMatchers.eq(7L));
    org.mockito.Mockito.verify(staffShifts).findInRange(start.capture(), end.capture(), org.mockito.ArgumentMatchers.eq(7L));
    for (OffsetDateTime t : start.getAllValues()) {
      assertThat(t).isNotEqualTo(OffsetDateTime.MIN);
      assertThat(t.getOffset()).isEqualTo(java.time.ZoneOffset.UTC);
      assertThat(t.getYear()).isLessThanOrEqualTo(1900);
    }
    for (OffsetDateTime t : end.getAllValues()) {
      assertThat(t).isNotEqualTo(OffsetDateTime.MAX);
      assertThat(t.getOffset()).isEqualTo(java.time.ZoneOffset.UTC);
      assertThat(t.getYear()).isGreaterThanOrEqualTo(9999);
    }
  }

  @Test
  void export_covers_shifts_availability_adjustments_calls_and_documents() {
    when(userRepo.findById(7L)).thenReturn(Optional.of(user));
    when(profileRepo.findByUserId(7L)).thenReturn(Optional.empty());
    when(chatMessages.findAllBySenderIdForExport(7L)).thenReturn(List.of());

    ScheduleShift shift = new ScheduleShift();
    shift.setStartAt(OffsetDateTime.parse("2026-09-01T09:00:00+02:00"));
    shift.setEndAt(OffsetDateTime.parse("2026-09-01T17:00:00+02:00"));
    when(scheduleShifts.findInRange(any(), any(), any(), any())).thenReturn(List.of(shift));

    StaffShift staffShift = new StaffShift();
    when(staffShifts.findInRange(any(), any(), any())).thenReturn(List.of(staffShift));

    AvailabilityRequest request = new AvailabilityRequest();
    when(availabilityRequests.findByUserIdOrderByCreatedAtDesc(7L)).thenReturn(List.of(request));

    SalaryAdjustment adjustment = new SalaryAdjustment();
    adjustment.setUserId(7L);
    adjustment.setYear(2026);
    adjustment.setMonth(9);
    adjustment.setName("Bonus");
    when(salaryAdjustments.findByUserId(7L)).thenReturn(List.of(adjustment));

    PayslipSnapshot snapshot = new PayslipSnapshot();
    snapshot.setUserId(7L);
    snapshot.setYear(2026);
    snapshot.setMonth(9);
    snapshot.setRole("USER");
    snapshot.setPayload("{\"gross\":1}");
    when(payslipSnapshots.findByUserId(7L)).thenReturn(List.of(snapshot));

    ProfileDocument document = new ProfileDocument();
    document.setFileName("license.pdf");
    document.setContentType("application/pdf");
    document.setSizeBytes(1234L);
    when(profileDocuments.findByUserIdOrderByIdDesc(7L)).thenReturn(List.of(document));

    GdprService.GdprExport export = service.export(7L);

    assertThat(export.shifts()).hasSize(1);
    assertThat(export.staffShifts()).hasSize(1);
    assertThat(export.availabilityRequests()).hasSize(1);
    assertThat(export.salaryAdjustments()).hasSize(1);
    assertThat(export.payslipSnapshots()).hasSize(1);
    assertThat(export.documents()).hasSize(1);
    assertThat(export.callHistory()).isEmpty();
    // Adjustments carry their month/name; documents their metadata.
    assertThat(export.salaryAdjustments().get(0)).containsEntry("name", "Bonus");
    assertThat(export.documents().get(0)).containsEntry("fileName", "license.pdf");
  }

  @Test
  void consent_state_is_latest_event_wins() {
    when(consentRepo.findTopByUserIdAndConsentTypeOrderByCreatedAtDesc(7L, GdprConsent.TYPE_TERMS))
        .thenReturn(Optional.of(new GdprConsent(7L, "pharm", GdprConsent.TYPE_TERMS, true)));
    when(consentRepo.findTopByUserIdAndConsentTypeOrderByCreatedAtDesc(7L, GdprConsent.TYPE_DATA_PROCESSING))
        .thenReturn(Optional.empty());

    var state = service.currentConsents(7L);

    assertThat(state).containsEntry(GdprConsent.TYPE_TERMS, true);
    assertThat(state).doesNotContainKey(GdprConsent.TYPE_DATA_PROCESSING);
  }

  @Test
  void consent_recording_stores_user_type_and_state() {
    when(consentRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

    GdprConsent saved = service.recordConsent(7L, "pharm", GdprConsent.TYPE_EMAIL_NOTIFICATIONS, false);

    assertThat(saved.getUserId()).isEqualTo(7L);
    assertThat(saved.getConsentType()).isEqualTo(GdprConsent.TYPE_EMAIL_NOTIFICATIONS);
    assertThat(saved.isGranted()).isFalse();
    assertThat(saved.getCreatedAt()).isNotNull();
  }
}
