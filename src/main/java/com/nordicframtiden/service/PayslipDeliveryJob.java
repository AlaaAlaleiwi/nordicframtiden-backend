package com.nordicframtiden.service;

import java.time.LocalDate;
import java.time.ZoneId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Automatic monthly payslip delivery. On the payslip-ready date — the 21st of
 * the payment month, or the previous working day when the 21st falls on a
 * weekend or Swedish public holiday ({@link PayrollCalendar}) — the previous
 * work month's payslip is queued for every USER/STAFF account, emailed as a
 * PDF and announced with a push notification. The queue drains on the same
 * and following mornings until every row is SENT (or FAILED after retries).
 */
@Service
public class PayslipDeliveryJob {

  private static final Logger log = LoggerFactory.getLogger(PayslipDeliveryJob.class);

  /** 04:00 server-side local time (Sweden) every day, after the GDPR jobs. */
  static final String CRON = "0 0 4 * * *";
  static final String ZONE = "Europe/Stockholm";

  private final PayslipDeliveryService deliveryService;

  public PayslipDeliveryJob(PayslipDeliveryService deliveryService) {
    this.deliveryService = deliveryService;
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

  @Scheduled(cron = PayslipDeliveryJob.CRON, zone = PayslipDeliveryJob.ZONE)
  public void run() {
    exclusively("payslip-delivery", () -> {
      LocalDate today = LocalDate.now(ZoneId.of(PayslipDeliveryJob.ZONE));
      int queued = deliveryService.queueIfReady(today);
      int delivered = deliveryService.deliverPending();
      if (queued > 0 || delivered > 0) {
        log.info("Payslip delivery job: {} queued, {} delivered", queued, delivered);
      }
    });
  }
}
