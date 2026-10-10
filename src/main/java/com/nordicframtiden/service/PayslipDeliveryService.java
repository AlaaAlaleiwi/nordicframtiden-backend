package com.nordicframtiden.service;

import com.nordicframtiden.chat.ChatPushSender;
import com.nordicframtiden.chat.ChatPushSubscriptionRepository;
import com.nordicframtiden.security.model.AppUser;
import com.nordicframtiden.security.model.Role;
import com.nordicframtiden.security.repo.AppUserRepository;
import com.nordicframtiden.security.repo.UserProfileRepository;
import com.nordicframtiden.service.model.NetSalaryResponse;
import com.nordicframtiden.service.model.PayslipDeliveryRequest;
import com.nordicframtiden.service.model.PayslipDeliveryRequestRepository;
import com.nordicframtiden.settings.EmailService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Automatic monthly payslip delivery. On the ready date — the 21st of the
 * payment month, or the previous working day when the 21st is not a working
 * day (see {@link PayrollCalendar}) — each USER/STAFF account gets the
 * previous work month's payslip emailed as a PDF and a "payslip ready" push
 * notification.
 *
 * Guarantees:
 * - Only the payslip for the ready month is queued, one row per account, and
 *   queueing self-heals daily for months not yet fully queued (a down day is
 *   repaired the next morning), bounded by the not-before work-month cutoff.
 * - Exactly once per (user, work month, role): enforced by the DB unique
 *   constraint, by per-row atomic claims (PENDING -> SENDING) so overlapping
 *   runs or a crash mid-send can never double-send, and by the SENT write
 *   happening only after a confirmed email success.
 * - Both channels fire in order: the email first (confirmed sent), then the
 *   push notification. If the email fails, no push goes out and the row
 *   retries the following days.
 *
 * No class-level transaction on purpose (same reasoning as the GDPR export
 * job): mail I/O must not hold a DB transaction open; each row update commits
 * on its own.
 */
@Service
public class PayslipDeliveryService {

    public static final String PUSH_TYPE = "payslip.ready";

    private static final Logger log = LoggerFactory.getLogger(PayslipDeliveryService.class);

    /** Max send attempts per queued row before it is marked FAILED. */
    static final int MAX_ATTEMPTS = 5;

    /** Claims older than this are considered dead and are re-queued. */
    static final Duration STALE_CLAIM_GRACE = Duration.ofMinutes(30);

    /**
     * Backstop cutoff: never queue for work months older than this many
     * months before the current one (protects the self-healing queue from
     * back-filling ancient months if the job was off for a long period).
     */
    static final int MAX_BACKFILL_MONTHS = 2;

    private final PayslipDeliveryRequestRepository requests;
    private final AppUserRepository users;
    private final UserProfileRepository profiles;
    private final PayslipFreezeService payslips;
    private final PayslipPdfBuilder pdfBuilder;
    private final EmailService emailService;
    private final ChatPushSender pushSender;
    private final ChatPushSubscriptionRepository pushSubscriptions;
    private final Clock clock;

    public PayslipDeliveryService(PayslipDeliveryRequestRepository requests,
                                  AppUserRepository users,
                                  UserProfileRepository profiles,
                                  PayslipFreezeService payslips,
                                  PayslipPdfBuilder pdfBuilder,
                                  EmailService emailService,
                                  ChatPushSender pushSender,
                                  ChatPushSubscriptionRepository pushSubscriptions,
                                  Clock clock) {
        this.requests = requests;
        this.users = users;
        this.profiles = profiles;
        this.payslips = payslips;
        this.pdfBuilder = pdfBuilder;
        this.emailService = emailService;
        this.pushSender = pushSender;
        this.pushSubscriptions = pushSubscriptions;
        this.clock = clock;
    }

    /* ===================== Queue building (daily job) ===================== */

    /**
     * Called daily by the job. Queues the previous work month once today has
     * reached its ready date (the 21st or previous working day). Returns the
     * number of rows newly queued. Idempotent: every account already has at
     * most one row per (user, work month, role), and queueing runs again on
     * later days until every eligible account has a row — a down or failing
     * day self-heals the next morning.
     */
    @Transactional
    public int queueIfReady(LocalDate today) {
        // On or AFTER the ready date: a run missed on the ready date itself
        // (instance down, deploy) is caught up the next morning instead of
        // that month never being queued. queueForMonth skips existing rows.
        YearMonth workMonth = YearMonth.from(today).minusMonths(1);
        if (today.isBefore(PayrollCalendar.readyDateFor(workMonth))) {
            return 0;
        }
        return queueForMonth(workMonth);
    }

    /**
     * Queues one row per enabled USER/STAFF account for {@code workMonth}.
     * Skips accounts without a usable profile email and accounts that already
     * have a row; duplicate inserts lose the unique-constraint race safely.
     */
    @Transactional
    public int queueForMonth(YearMonth workMonth) {
        // Backstop: never back-fill work months older than the cutoff.
        YearMonth cutoff = YearMonth.from(LocalDate.now(clock)).minusMonths(MAX_BACKFILL_MONTHS);
        if (workMonth.isBefore(cutoff)) {
            log.warn("Payslip delivery: refusing to back-fill work month {} (older than cutoff)", workMonth);
            return 0;
        }

        int queued = 0;
        for (Role role : List.of(Role.USER, Role.STAFF)) {
            for (AppUser user : users.findAllByRole(role)) {
                if (!user.isEnabled()) continue;
                String email = emailOf(user.getId());
                if (email == null) continue;
                if (requests.findByUserIdAndWorkYearAndWorkMonthAndRole(
                        user.getId(), workMonth.getYear(), workMonth.getMonthValue(), role.name()).isPresent()) {
                    continue;
                }
                try {
                    requests.save(new PayslipDeliveryRequest(user.getId(), email,
                        workMonth.getYear(), workMonth.getMonthValue(), role.name()));
                    queued++;
                } catch (DataIntegrityViolationException race) {
                    // Another worker inserted the same row concurrently: the
                    // unique constraint is the source of truth, keep going.
                }
            }
        }
        if (queued > 0) {
            log.info("Payslip delivery: queued {} recipient(s) for work month {}", queued, workMonth);
        }
        return queued;
    }

    private String emailOf(Long userId) {
        return profiles.findByUserId(userId)
            .map(profile -> profile.getEmail() == null ? null : profile.getEmail().trim())
            .filter(email -> !email.isBlank() && email.contains("@"))
            .orElse(null);
    }

    /* ===================== Delivery (daily job) ===================== */

    /**
     * Delivers every claimable PENDING row exactly once: each row is
     * atomically claimed before any work happens, the payslip for exactly the
     * row's month is resolved, emailed and confirmed, then the push fires, and
     * only then is the row marked SENT. Per-row failure records the error and
     * is retried on the following days until the attempts cap.
     */
    public int deliverPending() {
        int recovered = requests.releaseStaleClaims(Instant.now(clock).minus(STALE_CLAIM_GRACE));
        if (recovered > 0) {
            log.warn("Payslip delivery: recovered {} stale SENDING claim(s)", recovered);
        }

        List<PayslipDeliveryRequest> pending =
            requests.findByStatusOrderByCreatedAtAsc(PayslipDeliveryRequest.STATUS_PENDING);
        int delivered = 0;
        for (PayslipDeliveryRequest request : pending) {
            delivered += deliver(request) ? 1 : 0;
        }
        return delivered;
    }

    /**
     * Delivers only the requested row. Used by the admin resend action so its
     * result cannot be accidentally attributed to an unrelated pending row.
     */
    public boolean deliverOne(Long requestId) {
        return requests.findById(requestId).map(this::deliver).orElse(false);
    }

    /** Re-queues and delivers only the selected row, never unrelated pending work. */
    public ResendResult resendAndDeliver(Long requestId) {
        int queued = resend(requestId);
        if (queued == 0) return new ResendResult(false, false);
        return new ResendResult(true, deliverOne(requestId));
    }

    public record ResendResult(boolean queued, boolean sent) { }

    /** Delivers one row. Returns true when it was sent and marked SENT. */
    boolean deliver(PayslipDeliveryRequest request) {
        Instant now = Instant.now(clock);
        if (requests.claimIfPending(request.getId(), now) == 0) {
            // Claimed by another worker (or already terminal): never touch it.
            log.debug("Payslip delivery for row {}: claim lost, skipping", request.getId());
            return false;
        }
        try {
            Long userId = request.getUserId();
            String name = displayName(userId, "");
            if (name.isBlank()) name = "Employee";

            // Exactly the row's month, resolved (and finalized server-side when
            // the period is closed) from the immutable snapshot pipeline.
            NetSalaryResponse payslip = payslips.resolve(
                userId, request.getWorkYear(), request.getWorkMonth(), request.getRole());
            if (payslip.grossSalary() == null || payslip.grossSalary().compareTo(java.math.BigDecimal.ZERO) == 0) {
                requests.markSkippedIfSending(request.getId(), "Zero-gross payslip; no email sent");
                log.info("Payslip delivery skipped a zero-gross payslip (user id redacted)");
                return false;
            }
            byte[] pdf = pdfBuilder.build(
                name,
                request.getWorkYear(), request.getWorkMonth(),
                List.of(), payslip.grossSalary(), payslip.preliminaryTax(), payslip.netSalary(),
                payslip.totalHours());

            String monthLabel = "%04d-%02d".formatted(request.getWorkYear(), request.getWorkMonth());
            boolean sent = emailService.sendSalaryPdfEmail(request.getEmail(), name, pdf, monthLabel);
            if (!sent) {
                // Mail disabled or unconfigured: release the claim unpunished so
                // the next daily run retries once mail is available.
                requests.releaseClaimIfSending(request.getId());
                log.warn("Payslip delivery mail was not sent (disabled or unconfigured; user id redacted)");
                return false;
            }

            // Email confirmed delivered; now announce it.
            notifyReady(userId, request.getWorkYear(), request.getWorkMonth());

            // Terminal write only after both channels fired (push is best
            // effort); the conditional update means only the claim holder can
            // complete the row.
            if (requests.markSentIfSending(request.getId(), Instant.now(clock)) == 0) {
                log.warn("Payslip delivery claim was not held after send (user id redacted)");
                return false;
            }
            return true;
        } catch (Exception e) {
            // The DB retains the diagnostic, but do not store user/provider text that could contain PII or credentials.
            String message = e.getClass().getSimpleName();
            String truncated = message.length() > 1000 ? message.substring(0, 1000) : message;
            request.setAttempts(request.getAttempts() + 1);
            String nextStatus = request.getAttempts() >= MAX_ATTEMPTS
                ? PayslipDeliveryRequest.STATUS_FAILED
                : PayslipDeliveryRequest.STATUS_PENDING;
            requests.recordFailureIfSending(request.getId(), truncated, nextStatus, request.getAttempts());
            log.error("Payslip delivery failed (user id redacted; stored failure detail sanitized): {}",
                e.getClass().getSimpleName());
            return false;
        }
    }

    /**
     * Push notification "your payslip is ready" to every registered device of
     * the account. Fire-and-forget, mirroring the chat push contract; runs
     * only after the email was confirmed sent.
     */
    void notifyReady(Long userId, int workYear, int workMonth) {
        String username = users.findById(userId).map(AppUser::getUsername).orElse(null);
        if (username == null) {
            return;
        }
        var data = Map.of(
            "title", "Nordic Framtiden",
            "body", "Your payslip for " + workYear + "-" + String.format("%02d", workMonth) + " is ready",
            "url", "/pharmacist/salary",
            "type", PUSH_TYPE,
            "year", Integer.toString(workYear),
            "month", Integer.toString(workMonth));
        var subscriptions = pushSubscriptions.findByUserUsernameIn(List.of(username));
        if (subscriptions.isEmpty()) {
            return;
        }
        try {
            subscriptions.forEach(subscription ->
                pushSender.send(subscription.getFirebaseInstallationId(), data));
        } catch (RuntimeException e) {
            log.warn("Payslip ready notification could not be queued (user id redacted): {}",
                e.getClass().getSimpleName());
        }
    }

    /* ===================== Admin audit + resend ===================== */

    /** One row of the admin audit listing. */
    public record DeliveryRow(Long id, Long userId, String fullName, String email, int workYear,
        int workMonth, String role, String status, int attempts, String lastError,
        Instant claimedAt, Instant sentAt) {
    }

    /** Every delivery row for one work month, oldest first. */
    @Transactional(readOnly = true)
    public List<DeliveryRow> deliveriesForMonth(int workYear, int workMonth) {
        return requests.findByWorkYearAndWorkMonthOrderByCreatedAtAsc(workYear, workMonth).stream()
            .map(this::toRow)
            .toList();
    }

    private DeliveryRow toRow(PayslipDeliveryRequest r) {
        return new DeliveryRow(r.getId(), r.getUserId(), displayName(r.getUserId(), ""),
            r.getEmail(), r.getWorkYear(), r.getWorkMonth(), r.getRole(), r.getStatus(),
            r.getAttempts(), r.getLastError(), r.getClaimedAt(), r.getSentAt());
    }

    /**
     * Admin resend: re-queues one finished/failed row for immediate delivery
     * (the nightly queue is untouched; a PENDING/SENDING row is refused as it
     * is already being handled, and a missing row is reported as an error).
     * Returns the number of rows actually queued (0 or 1).
     */
    public int resend(Long requestId) {
        PayslipDeliveryRequest request = requests.findById(requestId)
            .orElseThrow(() -> new IllegalArgumentException("Delivery request not found"));
        if (PayslipDeliveryRequest.STATUS_PENDING.equals(request.getStatus())
            || PayslipDeliveryRequest.STATUS_SENDING.equals(request.getStatus())
            || PayslipDeliveryRequest.STATUS_SKIPPED.equals(request.getStatus())) {
            // Already queued or in flight — resending would risk a duplicate.
            return 0;
        }
        String email = emailOf(request.getUserId());
        if (email == null) email = request.getEmail();
        // Conditional database update prevents duplicate concurrent resends
        // and avoids overwriting a claim placed by the scheduled worker.
        int requeued = requests.requeueForResend(requestId, email);
        if (requeued > 0) {
            log.info("Payslip delivery row requeued for resend (request id redacted)");
        } else {
            log.warn("Payslip delivery row could not be requeued for resend (request id redacted)");
        }
        return requeued;
    }

    /**
     * Queues rows for every enabled USER/STAFF account missing one for
     * {@code workMonth} (e.g. hired after the ready date, or a late profile
     * email fix). Bounded by the same backstop as the nightly queue.
     */
    @Transactional
    public int queueMissing(YearMonth workMonth) {
        return queueForMonth(workMonth);
    }

    /* ===================== Status for the frontends ===================== */

    /**
     * Work month whose payslip was most recently auto-delivered to the user,
     * or empty when nothing was delivered yet. Powers the salary screens'
     * "payslip ready" banner.
     */
    @Transactional(readOnly = true)
    public Optional<YearMonth> lastDelivered(Long userId, String role) {
        return requests.findFirstByUserIdAndRoleAndStatusOrderByWorkYearDescWorkMonthDescSentAtDesc(
                userId, normalizeRole(role), PayslipDeliveryRequest.STATUS_SENT)
            .map(r -> YearMonth.of(r.getWorkYear(), r.getWorkMonth()));
    }

    /**
     * The ready date for the payment month that pays out {@code payoutMonth}:
     * the 21st, or the previous working day (see {@link PayrollCalendar}).
     */
    public LocalDate readyDateFor(YearMonth payoutMonth) {
        return PayrollCalendar.readyDateFor(payoutMonth);
    }

    private String displayName(Long userId, String fallback) {
        return profiles.findByUserId(userId)
            .map(profile -> profile.getFullName() == null ? null : profile.getFullName().trim())
            .filter(name -> !name.isBlank())
            .orElse(fallback);
    }

    private static String normalizeRole(String role) {
        return "STAFF".equalsIgnoreCase(role) ? "STAFF" : "USER";
    }
}
