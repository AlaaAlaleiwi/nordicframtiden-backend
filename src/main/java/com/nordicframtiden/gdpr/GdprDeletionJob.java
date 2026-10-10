package com.nordicframtiden.gdpr;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

/**
 * Executes approved GDPR deletion requests whose scheduled date has arrived.
 * Runs shortly after the export job so a same-night export+deletion sequence
 * stays ordered (export first, deletion second).
 */
@Service
public class GdprDeletionJob {

  private static final Logger log = LoggerFactory.getLogger(GdprDeletionJob.class);

  static final String CRON = "0 30 3 * * *";
  static final String ZONE = GdprExportMailJob.ZONE;
  // Not a real account: the name can never equal a deleted user's username,
  // so the admin self-delete guard never trips on it.
  static final String SYSTEM_PRINCIPAL = "system:gdpr-deletion-job";

  private final GdprDeletionService deletionService;

  public GdprDeletionJob(GdprDeletionService deletionService) {
    this.deletionService = deletionService;
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

  @Scheduled(cron = GdprDeletionJob.CRON, zone = GdprDeletionJob.ZONE)
  public void executeDueDeletions() {
    exclusively("gdpr-deletion", () -> {
      LocalDate today = LocalDate.now(ZoneId.of(GdprDeletionJob.ZONE));
      // Scheduler threads carry no authentication, but UserService.deleteUser is
      // @PreAuthorize("hasRole('ADMIN')"). Without this every deletion fails with
      // AuthenticationCredentialsNotFoundException and is retried forever.
      SecurityContext previous = SecurityContextHolder.getContext();
      SecurityContext system = SecurityContextHolder.createEmptyContext();
      system.setAuthentication(new UsernamePasswordAuthenticationToken(
          SYSTEM_PRINCIPAL, null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
      SecurityContextHolder.setContext(system);
      try {
        int executed = deletionService.executeDue(today);
        if (executed > 0) {
          log.info("GDPR deletion job: executed {} scheduled deletion(s)", executed);
        }
      } finally {
        SecurityContextHolder.setContext(previous);
      }
    });
  }
}
