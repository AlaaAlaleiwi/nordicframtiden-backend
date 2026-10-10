package com.nordicframtiden.gdpr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nordicframtiden.security.model.AppUser;
import com.nordicframtiden.security.model.Role;
import com.nordicframtiden.security.model.UserProfile;
import com.nordicframtiden.security.repo.AppUserRepository;
import com.nordicframtiden.security.repo.UserProfileRepository;
import com.nordicframtiden.security.service.UserService;
import com.nordicframtiden.settings.EmailService;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * TDD for the admin-reviewed deletion workflow: the data subject files a
 * request (nothing is deleted), the admin approves with a date >= 30 days
 * out, the job executes only after that date, and notices are emailed at
 * request/approval/completion. Protects schedule + payment history from
 * immediate-erasure accidents.
 */
@ExtendWith(MockitoExtension.class)
class GdprDeletionServiceTest {

  @Mock private GdprDeletionRequestRepository requests;
  @Mock private AppUserRepository userRepo;
  @Mock private UserProfileRepository profileRepo;
  @Mock private UserService userService;
  @Mock private EmailService emailService;
  @Mock private GdprService gdprService;
  @Mock private com.nordicframtiden.pharmacy.ScheduleShiftRepository scheduleShifts;
  @Mock private com.nordicframtiden.company.StaffShiftRepository staffShifts;

  private GdprDeletionService service;
  private AppUser user;

  @BeforeEach
  void setUp() {
    service = new GdprDeletionService(requests, userRepo, profileRepo, userService, emailService, gdprService,
        new DeletionPolicy(requests, scheduleShifts, staffShifts));
    user = new AppUser();
    user.setId(7L);
    user.setUsername("pharm");
    user.setRoles(new HashSet<>(Set.of(Role.USER)));
  }

  private UserProfile profileWithEmail(String email) {
    UserProfile p = new UserProfile();
    p.setUser(user);
    p.setFullName("Anna Andersson");
    p.setEmail(email);
    return p;
  }

  @Test
  void request_stores_pending_row_and_emails_notice_nothing_is_deleted() {
    when(requests.findTopByUserIdAndStatusInOrderByCreatedAtDesc(7L,
        List.of(GdprDeletionRequest.STATUS_PENDING, GdprDeletionRequest.STATUS_APPROVED)))
        .thenReturn(Optional.empty());
    when(userRepo.findById(7L)).thenReturn(Optional.of(user));
    when(profileRepo.findByUserId(7L)).thenReturn(Optional.of(profileWithEmail("anna@example.com")));
    when(requests.save(any())).thenAnswer(inv -> inv.getArgument(0));
    when(emailService.sendGdprDeletionReceivedEmail(anyString(), anyString())).thenReturn(true);

    GdprDeletionRequest saved = service.request(7L, "please remove me");

    assertThat(saved.getStatus()).isEqualTo(GdprDeletionRequest.STATUS_PENDING);
    assertThat(saved.getReason()).isEqualTo("please remove me");
    assertThat(saved.getEmail()).isEqualTo("anna@example.com");
    // Art. 7 erasure-request audit marker:
    verify(gdprService).recordConsent(7L, "pharm", GdprConsent.TYPE_ERASURE_REQUEST, true);
    // Notice of receipt:
    verify(emailService).sendGdprDeletionReceivedEmail("anna@example.com", "Anna Andersson");
    // Crucially: nothing deleted at request time.
    verify(userService, never()).deleteUser(any());
  }

  @Test
  void request_is_rejected_while_another_request_is_open() {
    when(requests.findTopByUserIdAndStatusInOrderByCreatedAtDesc(7L,
        List.of(GdprDeletionRequest.STATUS_PENDING, GdprDeletionRequest.STATUS_APPROVED)))
        .thenReturn(Optional.of(new GdprDeletionRequest(7L, "pharm", "anna@example.com", null)));

    assertThatThrownBy(() -> service.request(7L, null))
        .isInstanceOf(IllegalStateException.class);
    verify(requests, never()).save(any());
  }

  @Test
  void admin_cannot_file_a_deletion_request_through_self_service() {
    when(requests.findTopByUserIdAndStatusInOrderByCreatedAtDesc(7L,
        List.of(GdprDeletionRequest.STATUS_PENDING, GdprDeletionRequest.STATUS_APPROVED)))
        .thenReturn(Optional.empty());
    user.setRoles(new HashSet<>(Set.of(Role.ADMIN)));
    when(userRepo.findById(7L)).thenReturn(Optional.of(user));

    assertThatThrownBy(() -> service.request(7L, null))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void approve_fixes_a_date_emails_the_user_and_schedules_execution() {
    GdprDeletionRequest pending = new GdprDeletionRequest(7L, "pharm", "anna@example.com", null);
    when(requests.findById(1L)).thenReturn(Optional.of(pending));
    when(requests.save(any())).thenAnswer(inv -> inv.getArgument(0));
    when(profileRepo.findByUserId(7L)).thenReturn(Optional.of(profileWithEmail("anna@example.com")));
    when(emailService.sendGdprDeletionScheduledEmail(anyString(), anyString(), any())).thenReturn(true);
    LocalDate date = LocalDate.now(java.time.ZoneId.of("Europe/Stockholm"))
        .plusDays(GdprDeletionRequest.MIN_GRACE_DAYS);

    GdprDeletionRequest approved = service.approve(1L, "boss", date);

    assertThat(approved.getStatus()).isEqualTo(GdprDeletionRequest.STATUS_APPROVED);
    assertThat(approved.getScheduledDate()).isEqualTo(date);
    assertThat(approved.getApprovedBy()).isEqualTo("boss");
    verify(emailService).sendGdprDeletionScheduledEmail("anna@example.com", "Anna Andersson", date);
    verify(userService, never()).deleteUser(any());
  }

  @Test
  void approve_clamps_dates_sooner_than_the_policy_floor() {
    GdprDeletionRequest pending = new GdprDeletionRequest(7L, "pharm", "anna@example.com", null);
    when(requests.findById(1L)).thenReturn(Optional.of(pending));
    when(requests.save(any())).thenAnswer(inv -> inv.getArgument(0));
    when(profileRepo.findByUserId(7L)).thenReturn(Optional.of(profileWithEmail("anna@example.com")));
    when(emailService.sendGdprDeletionScheduledEmail(anyString(), anyString(), any())).thenReturn(true);
    LocalDate tooSoon = LocalDate.now(java.time.ZoneId.of("Europe/Stockholm")).plusDays(10);

    GdprDeletionRequest approved = service.approve(1L, "boss", tooSoon);

    // Clamped to the policy floor (no shifts -> MIN_GRACE_DAYS out), never rejected.
    assertThat(approved.getScheduledDate())
        .isEqualTo(LocalDate.now(java.time.ZoneId.of("Europe/Stockholm"))
            .plusDays(GdprDeletionRequest.MIN_GRACE_DAYS));
    verify(emailService).sendGdprDeletionScheduledEmail(
        "anna@example.com", "Anna Andersson", approved.getScheduledDate());
  }

  @Test
  void reject_declines_with_reason_and_no_email_is_mandated() {
    GdprDeletionRequest pending = new GdprDeletionRequest(7L, "pharm", "anna@example.com", null);
    when(requests.findById(1L)).thenReturn(Optional.of(pending));
    when(requests.save(any())).thenAnswer(inv -> inv.getArgument(0));

    GdprDeletionRequest rejected = service.reject(1L, "boss", "payroll audit in progress");

    assertThat(rejected.getStatus()).isEqualTo(GdprDeletionRequest.STATUS_REJECTED);
    assertThat(rejected.getRejectedReason()).isEqualTo("payroll audit in progress");
    verify(userService, never()).deleteUser(any());
  }

  @Test
  void cancel_lets_the_requester_withdraw_an_open_request() {
    GdprDeletionRequest approved = new GdprDeletionRequest(7L, "pharm", "anna@example.com", null);
    approved.setStatus(GdprDeletionRequest.STATUS_APPROVED);
    when(requests.findById(1L)).thenReturn(Optional.of(approved));
    when(requests.save(any())).thenAnswer(inv -> inv.getArgument(0));

    GdprDeletionRequest cancelled = service.cancel(7L, 1L);

    assertThat(cancelled.getStatus()).isEqualTo(GdprDeletionRequest.STATUS_CANCELLED);
    verify(userService, never()).deleteUser(any());
  }

  @Test
  void cancel_cannot_rewrite_a_rejected_request() {
    GdprDeletionRequest rejected = new GdprDeletionRequest(7L, "pharm", "anna@example.com", null);
    rejected.setStatus(GdprDeletionRequest.STATUS_REJECTED);
    when(requests.findById(1L)).thenReturn(Optional.of(rejected));

    assertThatThrownBy(() -> service.cancel(7L, 1L)).isInstanceOf(IllegalStateException.class);
    assertThat(rejected.getStatus()).isEqualTo(GdprDeletionRequest.STATUS_REJECTED);
  }

  @Test
  void cancel_rejects_someone_elses_request() {
    GdprDeletionRequest other = new GdprDeletionRequest(99L, "other", "x@x.se", null);
    when(requests.findById(1L)).thenReturn(Optional.of(other));

    assertThatThrownBy(() -> service.cancel(7L, 1L))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void job_executes_due_approved_requests_and_emails_completion() {
    GdprDeletionRequest due = new GdprDeletionRequest(7L, "pharm", "anna@example.com", null);
    due.setStatus(GdprDeletionRequest.STATUS_APPROVED);
    due.setScheduledDate(LocalDate.now(java.time.ZoneId.of("Europe/Stockholm")));
    when(requests.findByStatusAndScheduledDateLessThanEqual(
        GdprDeletionRequest.STATUS_APPROVED, LocalDate.now(java.time.ZoneId.of("Europe/Stockholm"))))
        .thenReturn(List.of(due));
    when(emailService.sendGdprDeletionCompletedEmail(anyString(), anyString())).thenReturn(true);

    int executed = service.executeDue(LocalDate.now(java.time.ZoneId.of("Europe/Stockholm")));

    assertThat(executed).isEqualTo(1);
    ArgumentCaptor<GdprDeletionRequest> saved = ArgumentCaptor.forClass(GdprDeletionRequest.class);
    verify(requests).save(saved.capture());
    assertThat(saved.getValue().getStatus()).isEqualTo(GdprDeletionRequest.STATUS_DELETED);
    assertThat(saved.getValue().getDeletedAt()).isNotNull();
    verify(userService).deleteUser(7L);
    verify(emailService).sendGdprDeletionCompletedEmail("anna@example.com", "pharm");
  }

  @Test
  void job_skips_approved_requests_whose_date_has_not_arrived() {
    LocalDate today = LocalDate.now(java.time.ZoneId.of("Europe/Stockholm"));
    when(requests.findByStatusAndScheduledDateLessThanEqual(
        GdprDeletionRequest.STATUS_APPROVED, today)).thenReturn(List.of());

    int executed = service.executeDue(today);

    assertThat(executed).isZero();
    verify(userService, never()).deleteUser(any());
  }

  @Test
  void job_continues_when_one_deletion_fails() {
    GdprDeletionRequest first = new GdprDeletionRequest(7L, "pharm", "anna@example.com", null);
    first.setStatus(GdprDeletionRequest.STATUS_APPROVED);
    GdprDeletionRequest second = new GdprDeletionRequest(8L, "other", "x@x.se", null);
    second.setStatus(GdprDeletionRequest.STATUS_APPROVED);
    LocalDate today = LocalDate.now(java.time.ZoneId.of("Europe/Stockholm"));
    when(requests.findByStatusAndScheduledDateLessThanEqual(
        GdprDeletionRequest.STATUS_APPROVED, today)).thenReturn(List.of(first, second));
    org.mockito.Mockito.doThrow(new IllegalStateException("fk constraint"))
        .when(userService).deleteUser(7L);
    lenient().when(emailService.sendGdprDeletionCompletedEmail(anyString(), anyString())).thenReturn(true);

    int executed = service.executeDue(today);

    assertThat(executed).isEqualTo(1);
    verify(userService).deleteUser(8L);
  }
}
