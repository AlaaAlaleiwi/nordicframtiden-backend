package com.nordicframtiden.service;

import com.nordicframtiden.chat.ChatPushSender;
import com.nordicframtiden.chat.ChatPushSubscription;
import com.nordicframtiden.chat.ChatPushSubscriptionRepository;
import com.nordicframtiden.security.model.AppUser;
import com.nordicframtiden.security.model.Role;
import com.nordicframtiden.security.repo.AppUserRepository;
import com.nordicframtiden.security.repo.UserProfileRepository;
import com.nordicframtiden.security.model.UserProfile;
import com.nordicframtiden.service.model.NetSalaryResponse;
import com.nordicframtiden.service.model.PayslipDeliveryRequest;
import com.nordicframtiden.service.model.PayslipDeliveryRequestRepository;
import com.nordicframtiden.settings.EmailService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TDD for the exactly-once payslip delivery: the row is atomically claimed
 * before any send, the email is confirmed before the push fires, SENT is
 * written only after both, overlapping runs/crashes cannot double-send, and
 * only the ready month is ever queued — once per account and month.
 */
@ExtendWith(MockitoExtension.class)
class PayslipDeliveryServiceTest {

    @Mock private PayslipDeliveryRequestRepository requests;
    @Mock private AppUserRepository users;
    @Mock private UserProfileRepository profiles;
    @Mock private com.nordicframtiden.service.PayslipFreezeService payslips;
    @Mock private PayslipPdfBuilder pdfBuilder;
    @Mock private EmailService emailService;
    @Mock private ChatPushSender pushSender;
    @Mock private ChatPushSubscriptionRepository pushSubscriptions;

    private static final Instant NOW = Instant.parse("2026-09-21T10:00:00Z");
    private final Clock clock = Clock.fixed(NOW, ZoneId.of("Europe/Stockholm"));

    private PayslipDeliveryService service;
    private AppUser user;
    private UserProfile profile;
    private PayslipDeliveryRequest row;

    @BeforeEach
    void setUp() {
        service = new PayslipDeliveryService(requests, users, profiles, payslips, pdfBuilder,
            emailService, pushSender, pushSubscriptions, clock);

        user = new AppUser();
        user.setId(7L);
        user.setUsername("pharm");
        user.setRoles(new java.util.HashSet<>(java.util.Set.of(Role.USER)));
        profile = new UserProfile();
        profile.setUser(user);
        profile.setFullName("Anna Andersson");
        profile.setEmail("anna@example.com");

        row = new PayslipDeliveryRequest(7L, "anna@example.com", 2026, 8, "USER");
        row.setId(100L);

        lenient().when(requests.save(any())).thenAnswer(inv -> inv.getArgument(0));
        // Default: the claim always succeeds (the happy-path protocol); the
        // failure tests re-stub these, so keep them lenient.
        lenient().when(requests.claimIfPending(eq(100L), any())).thenReturn(1);
        lenient().when(requests.markSentIfSending(eq(100L), any())).thenReturn(1);
    }

    private NetSalaryResponse payslip() {
        return payslip(7L, new BigDecimal("24000"));
    }

    private NetSalaryResponse payslip(long userId, BigDecimal grossSalary) {
        return new NetSalaryResponse(userId, "2026-08",
            new BigDecimal("200"), new BigDecimal("120"),
            grossSalary, null, null, null, null,
            new BigDecimal("4800"), new BigDecimal("19200"));
    }

    private void stubHappyPath() {
        when(profiles.findByUserId(7L)).thenReturn(Optional.of(profile));
        when(payslips.resolve(7L, 2026, 8, "USER")).thenReturn(payslip());
        when(pdfBuilder.build(any(), anyInt(), anyInt(), any(), any(), any(), any(), any(), any(), any()))
            .thenReturn("%PDF-1.4 test".getBytes());
        when(emailService.sendSalaryPdfEmail(eq("anna@example.com"), eq("Anna Andersson"), any(byte[].class), eq("2026-08")))
            .thenReturn(true);
        when(users.findById(7L)).thenReturn(Optional.of(user));
        when(pushSubscriptions.findByUserUsernameIn(List.of("pharm"))).thenReturn(List.of(subscription("fid-1")));
    }

    @Test
    void deliver_claimsBeforeDoingAnyWork_andSendsExactlyTheRowsMonth() {
        stubHappyPath();

        boolean sent = service.deliver(row);

        assertThat(sent).isTrue();
        InOrder order = inOrder(requests, emailService, pushSender);
        // 1. Atomic claim first.
        order.verify(requests).claimIfPending(eq(100L), any());
        // 2. Exactly the row's month is emailed...
        order.verify(emailService).sendSalaryPdfEmail(eq("anna@example.com"), eq("Anna Andersson"),
            any(byte[].class), eq("2026-08"));
        // 3. ...then the push, then the terminal SENT write.
        order.verify(pushSender).send(eq("fid-1"), any());
        order.verify(requests).markSentIfSending(eq(100L), any());
    }

    @Test
    void deliver_rendersTheLatestFinalizedRevisionWithItsNumberAndTaxFreeLine() {
        stubHappyPath();
        // Revision 2 carries a 500 SEK tax-free reimbursement: 24000 - 4800 + 500 = 19700.
        NetSalaryResponse corrected = new NetSalaryResponse(7L, "2026-08", new BigDecimal("200"),
            null, null, new BigDecimal("120"), new BigDecimal("24000"), null, null, null, null,
            new BigDecimal("4800"), new BigDecimal("19700"), new BigDecimal("4800"), BigDecimal.ZERO,
            new BigDecimal("500"), null, List.of(), null, null, null);
        when(payslips.history(7L, 2026, 8, "USER")).thenReturn(List.of(
            new PayslipFreezeService.Revision(1, "system", NOW, null, null, payslip()),
            new PayslipFreezeService.Revision(2, "admin", NOW, "Missing allowance", null, corrected)));

        assertThat(service.deliver(row)).isTrue();

        verify(pdfBuilder).build(eq("Anna Andersson"), eq(2026), eq(8), eq(List.of()),
            eq(new BigDecimal("24000")), eq(new BigDecimal("4800")), eq(new BigDecimal("500")),
            eq(new BigDecimal("19700")), eq(new BigDecimal("120")), eq(2));
    }

    @Test
    void deliver_draftPayslipHasNoRevisionNumber() {
        stubHappyPath();

        assertThat(service.deliver(row)).isTrue();

        verify(pdfBuilder).build(eq("Anna Andersson"), eq(2026), eq(8), eq(List.of()),
            eq(new BigDecimal("24000")), eq(new BigDecimal("4800")), eq(BigDecimal.ZERO),
            eq(new BigDecimal("19200")), eq(new BigDecimal("120")), eq((Integer) null));
    }

    @Test
    void deliver_skipsTheRowWhenAnotherWorkerHoldsTheClaim() {
        when(requests.claimIfPending(eq(100L), any())).thenReturn(0);

        boolean sent = service.deliver(row);

        assertThat(sent).isFalse();
        verify(emailService, never()).sendSalaryPdfEmail(anyString(), anyString(), any(), anyString());
        verify(pushSender, never()).send(anyString(), any());
        verify(requests, never()).markSentIfSending(anyLong(), any());
    }

    @Test
    void deliver_recordsFailure_andRetriesWhenTheEmailThrows() {
        when(profiles.findByUserId(7L)).thenReturn(Optional.of(profile));
        when(payslips.resolve(7L, 2026, 8, "USER")).thenReturn(payslip());
        when(pdfBuilder.build(any(), anyInt(), anyInt(), any(), any(), any(), any(), any(), any(), any()))
            .thenReturn("%PDF-1.4".getBytes());
        when(emailService.sendSalaryPdfEmail(anyString(), anyString(), any(byte[].class), anyString()))
            .thenThrow(new IllegalStateException("smtp down"));

        boolean sent = service.deliver(row);

        assertThat(sent).isFalse();
        verify(requests).recordFailureIfSending(eq(100L), eq("IllegalStateException"),
            eq(PayslipDeliveryRequest.STATUS_PENDING), eq(1));
        // No push went out and no SENT was recorded.
        verify(pushSender, never()).send(anyString(), any());
        verify(requests, never()).markSentIfSending(anyLong(), any());
    }

    @Test
    void deliver_skipsZeroGrossPayslipsWithoutSendingOrRetrying() {
        when(payslips.resolve(7L, 2026, 8, "USER")).thenReturn(payslip(7L, BigDecimal.ZERO));

        boolean sent = service.deliver(row);

        assertThat(sent).isFalse();
        verify(requests).markSkippedIfSending(100L, "Zero-gross payslip; no email sent");
        verify(pdfBuilder, never()).build(any(), anyInt(), anyInt(), any(), any(), any(), any(), any(), any(), any());
        verify(emailService, never()).sendSalaryPdfEmail(anyString(), anyString(), any(byte[].class), anyString());
        verify(pushSender, never()).send(anyString(), any());
        verify(requests, never()).recordFailureIfSending(anyLong(), anyString(), anyString(), anyInt());
        verify(requests, never()).markSentIfSending(anyLong(), any());
    }

    @Test
    void deliver_marksFailedAfterTheAttemptCap() {
        row.setAttempts(PayslipDeliveryService.MAX_ATTEMPTS - 1);
        when(requests.claimIfPending(eq(100L), any())).thenReturn(1);
        when(profiles.findByUserId(7L)).thenReturn(Optional.of(profile));
        when(payslips.resolve(7L, 2026, 8, "USER")).thenReturn(payslip());
        when(pdfBuilder.build(any(), anyInt(), anyInt(), any(), any(), any(), any(), any(), any(), any()))
            .thenReturn("%PDF-1.4".getBytes());
        when(emailService.sendSalaryPdfEmail(anyString(), anyString(), any(byte[].class), anyString()))
            .thenThrow(new IllegalStateException("smtp down again"));

        service.deliver(row);

        verify(requests).recordFailureIfSending(eq(100L), eq("IllegalStateException"),
            eq(PayslipDeliveryRequest.STATUS_FAILED), eq(PayslipDeliveryService.MAX_ATTEMPTS));
    }

    @Test
    void deliver_leavesPendingAndReleasesTheClaimWhenMailIsDisabled() {
        when(profiles.findByUserId(7L)).thenReturn(Optional.of(profile));
        when(payslips.resolve(7L, 2026, 8, "USER")).thenReturn(payslip());
        when(pdfBuilder.build(any(), anyInt(), anyInt(), any(), any(), any(), any(), any(), any(), any()))
            .thenReturn("%PDF-1.4".getBytes());
        when(emailService.sendSalaryPdfEmail(anyString(), anyString(), any(byte[].class), anyString()))
            .thenReturn(false);

        boolean sent = service.deliver(row);

        assertThat(sent).isFalse();
        // Claim released without burning an attempt; nothing terminal happened.
        verify(requests).releaseClaimIfSending(100L);
        verify(requests, never()).markSentIfSending(anyLong(), any());
        verify(requests, never()).recordFailureIfSending(anyLong(), anyString(), anyString(), anyInt());
        verify(pushSender, never()).send(anyString(), any());
    }

    @Test
    void deliver_skipsThePushWhenNoDevicesAreRegistered_butStillCompletes() {
        when(profiles.findByUserId(7L)).thenReturn(Optional.of(profile));
        when(payslips.resolve(7L, 2026, 8, "USER")).thenReturn(payslip());
        when(pdfBuilder.build(any(), anyInt(), anyInt(), any(), any(), any(), any(), any(), any(), any()))
            .thenReturn("%PDF-1.4".getBytes());
        when(emailService.sendSalaryPdfEmail(anyString(), anyString(), any(byte[].class), anyString()))
            .thenReturn(true);
        when(users.findById(7L)).thenReturn(Optional.of(user));
        when(pushSubscriptions.findByUserUsernameIn(List.of("pharm"))).thenReturn(List.of());

        boolean sent = service.deliver(row);

        assertThat(sent).isTrue();
        verify(pushSender, never()).send(anyString(), any());
        verify(requests).markSentIfSending(eq(100L), any());
    }

    @Test
    void deliver_stillMarksSentWhenThePushSenderThrows() {
        stubHappyPath();
        doThrow(new RuntimeException("fcm down")).when(pushSender).send(anyString(), any());

        boolean sent = service.deliver(row);

        // Email already went out; a push outage must not cause a resend loop.
        assertThat(sent).isTrue();
        verify(requests).markSentIfSending(eq(100L), any());
    }

    @Test
    void deliverPending_releasesStaleClaims_beforeClaimingFreshRows() {
        when(requests.releaseStaleClaims(any())).thenReturn(0);
        when(requests.findByStatusOrderByCreatedAtAsc(PayslipDeliveryRequest.STATUS_PENDING))
            .thenReturn(List.of());

        service.deliverPending();

        verify(requests).releaseStaleClaims(NOW.minus(PayslipDeliveryService.STALE_CLAIM_GRACE));
    }

    @Test
    void queueIfReady_queuesEnabledAccountsOnceForTheReadyMonthOnly() {
        when(users.findAllByRole(Role.USER)).thenReturn(List.of(user));
        when(users.findAllByRole(Role.STAFF)).thenReturn(List.of());
        when(profiles.findByUserId(7L)).thenReturn(Optional.of(profile));
        when(requests.findByUserIdAndWorkYearAndWorkMonthAndRole(7L, 2026, 8, "USER"))
            .thenReturn(Optional.empty());

        int queued = service.queueIfReady(LocalDate.of(2026, 9, 21));

        assertThat(queued).isEqualTo(1);
        ArgumentCaptor<PayslipDeliveryRequest> saved = ArgumentCaptor.forClass(PayslipDeliveryRequest.class);
        verify(requests).save(saved.capture());
        assertThat(saved.getValue().getUserId()).isEqualTo(7L);
        assertThat(saved.getValue().getWorkYear()).isEqualTo(2026);
        assertThat(saved.getValue().getWorkMonth()).isEqualTo(8);
        assertThat(saved.getValue().getRole()).isEqualTo("USER");
        assertThat(saved.getValue().getStatus()).isEqualTo(PayslipDeliveryRequest.STATUS_PENDING);
    }

    @Test
    void queueIfReady_neverQueuesOnAnOrdinaryDay() {
        int queued = service.queueIfReady(LocalDate.of(2026, 9, 15));

        assertThat(queued).isZero();
        verify(users, never()).findAllByRole(any());
        verify(requests, never()).save(any());
    }

    @Test
    void queueIfReady_catchesUpAfterAMissedReadyDate() {
        // The instance was down on Sep 21; the Sep 23 run must still queue August.
        when(users.findAllByRole(Role.USER)).thenReturn(List.of(user));
        when(users.findAllByRole(Role.STAFF)).thenReturn(List.of());
        when(profiles.findByUserId(7L)).thenReturn(Optional.of(profile));
        when(requests.findByUserIdAndWorkYearAndWorkMonthAndRole(7L, 2026, 8, "USER"))
            .thenReturn(Optional.empty());

        assertThat(service.queueIfReady(LocalDate.of(2026, 9, 23))).isEqualTo(1);
    }

    @Test
    void queueIfReady_queuesOnlyOncePerAccountAndMonth() {
        when(users.findAllByRole(Role.USER)).thenReturn(List.of(user));
        when(users.findAllByRole(Role.STAFF)).thenReturn(List.of());
        when(profiles.findByUserId(7L)).thenReturn(Optional.of(profile));
        when(requests.findByUserIdAndWorkYearAndWorkMonthAndRole(7L, 2026, 8, "USER"))
            .thenReturn(Optional.of(row));

        int queued = service.queueIfReady(LocalDate.of(2026, 9, 21));

        assertThat(queued).isZero();
        verify(requests, never()).save(any());
    }

    @Test
    void queueIfReady_skipsAccountsWithoutUsableEmail() {
        AppUser noEmailUser = new AppUser();
        noEmailUser.setId(8L);
        noEmailUser.setUsername("nonet");
        noEmailUser.setRoles(new java.util.HashSet<>(java.util.Set.of(Role.USER)));

        UserProfile emptyProfile = new UserProfile();
        emptyProfile.setUser(noEmailUser);
        emptyProfile.setEmail("not-an-email");

        when(users.findAllByRole(Role.USER)).thenReturn(List.of(user, noEmailUser));
        when(users.findAllByRole(Role.STAFF)).thenReturn(List.of());
        when(profiles.findByUserId(7L)).thenReturn(Optional.of(profile));
        when(profiles.findByUserId(8L)).thenReturn(Optional.of(emptyProfile));
        when(requests.findByUserIdAndWorkYearAndWorkMonthAndRole(7L, 2026, 8, "USER"))
            .thenReturn(Optional.empty());

        int queued = service.queueIfReady(LocalDate.of(2026, 9, 21));

        // Invalid email skipped; the valid account is queued.
        assertThat(queued).isEqualTo(1);
        verify(requests).save(any(PayslipDeliveryRequest.class));
    }

    @Test
    void queueForMonth_refusesToBackFillMonthsOlderThanTheCutoff() {
        // Current month per the fixed clock: 2026-09. Two months back = 2026-07.
        int queued = service.queueForMonth(YearMonth.of(2026, 6));

        assertThat(queued).isZero();
        verify(users, never()).findAllByRole(any());
    }

    /* ===================== Admin audit + resend ===================== */

    @Test
    void deliverOne_sendsOnlyTheRequestedRow_withoutDrainingTheQueue() {
        stubHappyPath();
        when(requests.findById(100L)).thenReturn(Optional.of(row));

        boolean sent = service.deliverOne(100L);

        assertThat(sent).isTrue();
        verify(requests, never()).findByStatusOrderByCreatedAtAsc(anyString());
        verify(requests, never()).releaseStaleClaims(any());
        verify(emailService).sendSalaryPdfEmail(eq("anna@example.com"), eq("Anna Andersson"),
            any(byte[].class), eq("2026-08"));
    }

    @Test
    void resend_requeuesAFailedRow_andDeliversOnlyThatRow() {
        PayslipDeliveryRequest failed = new PayslipDeliveryRequest(7L, "old@example.com", 2026, 8, "USER");
        failed.setId(101L);
        failed.setStatus(PayslipDeliveryRequest.STATUS_FAILED);
        failed.setAttempts(PayslipDeliveryService.MAX_ATTEMPTS);
        failed.setLastError("smtp down");
        when(requests.findById(101L)).thenReturn(Optional.of(failed));
        when(requests.requeueForResend(101L, "anna@example.com")).thenAnswer(invocation -> {
            failed.setEmail(invocation.getArgument(1));
            failed.setStatus(PayslipDeliveryRequest.STATUS_PENDING);
            failed.setAttempts(0);
            failed.setClaimedAt(null);
            failed.setSentAt(null);
            failed.setLastError(null);
            return 1;
        });
        when(requests.claimIfPending(eq(101L), any())).thenReturn(1);
        when(requests.markSentIfSending(eq(101L), any())).thenReturn(1);
        stubHappyPath();

        PayslipDeliveryService.ResendResult result = service.resendAndDeliver(101L);

        assertThat(result.queued()).isTrue();
        assertThat(result.sent()).isTrue();
        assertThat(failed.getStatus()).isEqualTo(PayslipDeliveryRequest.STATUS_PENDING);
        assertThat(failed.getAttempts()).isZero();
        assertThat(failed.getClaimedAt()).isNull();
        assertThat(failed.getSentAt()).isNull();
        verify(requests, never()).findByStatusOrderByCreatedAtAsc(anyString());
        verify(requests, never()).releaseStaleClaims(any());
        verify(requests).requeueForResend(101L, "anna@example.com");
    }

    @Test
    void resend_refusesRowsAlreadyQueuedOrInFlight() {
        PayslipDeliveryRequest pending = new PayslipDeliveryRequest(7L, "anna@example.com", 2026, 8, "USER");
        pending.setId(102L);
        pending.setStatus(PayslipDeliveryRequest.STATUS_PENDING);
        when(requests.findById(102L)).thenReturn(Optional.of(pending));

        assertThat(service.resend(102L)).isZero();
        verify(requests, never()).requeueForResend(anyLong(), anyString());

        pending.setStatus(PayslipDeliveryRequest.STATUS_SENDING);
        assertThat(service.resend(102L)).isZero();
        verify(requests, never()).requeueForResend(anyLong(), anyString());
    }

    @Test
    void resend_throwsForUnknownRows() {
        when(requests.findById(999L)).thenReturn(Optional.empty());

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.resend(999L))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void deliveriesForMonth_mapsEveryRowWithTheResolvedName() {
        PayslipDeliveryRequest sent = new PayslipDeliveryRequest(7L, "anna@example.com", 2026, 8, "USER");
        sent.setStatus(PayslipDeliveryRequest.STATUS_SENT);
        sent.setSentAt(Instant.now());
        when(requests.findByWorkYearAndWorkMonthOrderByCreatedAtAsc(2026, 8)).thenReturn(List.of(sent));
        when(profiles.findByUserId(7L)).thenReturn(Optional.of(profile));

        List<PayslipDeliveryService.DeliveryRow> rows = service.deliveriesForMonth(2026, 8);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).id()).isEqualTo(sent.getId());
        assertThat(rows.get(0).fullName()).isEqualTo("Anna Andersson");
        assertThat(rows.get(0).status()).isEqualTo("SENT");
        assertThat(rows.get(0).email()).isEqualTo("anna@example.com");
    }

    @Test
    void queueMissing_reusesTheBackstoppedQueue() {
        when(users.findAllByRole(Role.USER)).thenReturn(List.of(user));
        when(users.findAllByRole(Role.STAFF)).thenReturn(List.of());
        when(profiles.findByUserId(7L)).thenReturn(Optional.of(profile));
        when(requests.findByUserIdAndWorkYearAndWorkMonthAndRole(7L, 2026, 8, "USER"))
            .thenReturn(Optional.empty());

        int queued = service.queueMissing(YearMonth.of(2026, 8));

        assertThat(queued).isEqualTo(1);
        verify(requests).save(any(PayslipDeliveryRequest.class));
    }

    @Test
    void lastDelivered_returnsTheLatestSentMonth() {
        PayslipDeliveryRequest sent = new PayslipDeliveryRequest(7L, "anna@example.com", 2026, 8, "USER");
        sent.setStatus(PayslipDeliveryRequest.STATUS_SENT);
        sent.setSentAt(Instant.now());
        when(requests.findFirstByUserIdAndRoleAndStatusOrderByWorkYearDescWorkMonthDescSentAtDesc(7L, "USER", "SENT"))
            .thenReturn(Optional.of(sent));

        assertThat(service.lastDelivered(7L, "USER")).contains(YearMonth.of(2026, 8));
    }

    @Test
    void lastDelivered_emptyWhenNothingSentYet() {
        when(requests.findFirstByUserIdAndRoleAndStatusOrderByWorkYearDescWorkMonthDescSentAtDesc(7L, "USER", "SENT"))
            .thenReturn(Optional.empty());

        assertThat(service.lastDelivered(7L, "USER")).isEmpty();
    }

    /** Mockito matcher: string containing the needle (for error messages). */
    private static org.mockito.ArgumentMatcher<String> contains(String needle) {
        return value -> value != null && value.contains(needle);
    }

    private ChatPushSubscription subscription(String fid) {
        ChatPushSubscription subscription = new ChatPushSubscription();
        subscription.setFirebaseInstallationId(fid);
        return subscription;
    }
}
