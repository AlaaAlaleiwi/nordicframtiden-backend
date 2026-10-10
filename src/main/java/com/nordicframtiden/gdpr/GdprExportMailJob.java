package com.nordicframtiden.gdpr;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nordicframtiden.settings.EmailService;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Nightly GDPR data-export delivery (Art. 15/20). At 03:00 Europe/Stockholm
 * it processes every PENDING gdpr_export_request: builds the machine-readable
 * export, emails it as a JSON attachment to the address on file, and marks
 * the request SENT. Failures are retried on the following nights until
 * {@link #MAX_ATTEMPTS} is reached (then FAILED, so it stops retrying).
 * No class-level transaction on purpose: mail I/O must not hold a DB
 * transaction open; each row update commits on its own.
 */
@Service
public class GdprExportMailJob {

  private static final Logger log = LoggerFactory.getLogger(GdprExportMailJob.class);

  /** 03:00 server-side local time (Sweden) every day. */
  static final String CRON = "0 0 3 * * *";
  static final String ZONE = "Europe/Stockholm";
  static final int MAX_ATTEMPTS = 5;

  private final GdprExportRequestRepository requests;
  private final GdprService gdprService;
  private final EmailService emailService;
  private final ObjectMapper objectMapper;

  public GdprExportMailJob(
      GdprExportRequestRepository requests,
      GdprService gdprService,
      EmailService emailService,
      ObjectMapper objectMapper) {
    this.requests = requests;
    this.gdprService = gdprService;
    this.emailService = emailService;
    this.objectMapper = objectMapper;
  }

  private com.nordicframtiden.config.ClusterJobLock jobLock;

  /** Cross-instance lock (Cloud Run may run several instances); absent in unit tests. */
  @org.springframework.beans.factory.annotation.Autowired(required = false)
  void setJobLock(com.nordicframtiden.config.ClusterJobLock jobLock) {
    this.jobLock = jobLock;
  }

  private void exclusively(String name, Runnable job) {
    if (jobLock == null) job.run();
    else jobLock.runExclusively(name, job);
  }

  @Scheduled(cron = GdprExportMailJob.CRON, zone = GdprExportMailJob.ZONE)
  public void processPendingRequests() {
    exclusively("gdpr-export-mail", () -> {
      List<GdprExportRequest> pending =
          requests.findByStatusOrderByCreatedAtAsc(GdprExportRequest.STATUS_PENDING);
      if (pending.isEmpty()) {
        return;
      }
      log.info("GDPR export job: processing {} pending request(s)", pending.size());
      int delivered = 0;
      for (GdprExportRequest request : pending) {
        delivered += deliver(request) ? 1 : 0;
      }
      log.info("GDPR export job: {} delivered, {} still pending", delivered, pending.size() - delivered);
    });
  }

  /** Processes one request. Returns true when it was delivered and marked SENT. */
  boolean deliver(GdprExportRequest request) {
    try {
      GdprService.GdprExport export = gdprService.export(request.getUserId());
      byte[] json = objectMapper.writeValueAsBytes(export);
      String displayName = export.username();
      Object fullName = export.profile() != null ? export.profile().get("fullName") : null;
      if (fullName != null && !String.valueOf(fullName).isBlank()) {
        displayName = String.valueOf(fullName);
      }
      boolean sent = emailService.sendGdprExportEmail(request.getEmail(), displayName, json);
      if (!sent) {
        // Mail disabled or unconfigured: leave PENDING for the next run.
        log.warn("GDPR export mail was not sent (disabled or unconfigured; user id redacted)");
        return false;
      }
      request.setStatus(GdprExportRequest.STATUS_SENT);
      request.setSentAt(Instant.now());
      requests.save(request);
      return true;
    } catch (Exception e) {
      request.setAttempts(request.getAttempts() + 1);
      // Persist only a safe diagnostic category; exception text can include PII or credentials.
      request.setLastError(e.getClass().getSimpleName());
      if (request.getAttempts() >= MAX_ATTEMPTS) {
        request.setStatus(GdprExportRequest.STATUS_FAILED);
      }
      requests.save(request);
      log.error("GDPR export delivery failed (user id redacted; failure detail sanitized): {}",
          e.getClass().getSimpleName());
      return false;
    }
  }
}
