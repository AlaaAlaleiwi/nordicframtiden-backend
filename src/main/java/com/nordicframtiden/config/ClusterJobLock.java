package com.nordicframtiden.config;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Cross-instance mutual exclusion for @Scheduled jobs. Cloud Run can run
 * several instances, and each one fires every cron trigger — without this the
 * nightly jobs would send duplicate emails and race each other.
 *
 * Uses a PostgreSQL session-level advisory lock held on one dedicated
 * connection for the whole run (no extra table or dependency). The losing
 * instance skips the run; the job's next trigger tries again.
 */
@Component
public class ClusterJobLock {

  private static final Logger log = LoggerFactory.getLogger(ClusterJobLock.class);

  private final DataSource dataSource;

  public ClusterJobLock(DataSource dataSource) {
    this.dataSource = dataSource;
  }

  /** Runs {@code job} only if no other instance is running the job named {@code name}. */
  public void runExclusively(String name, Runnable job) {
    long key = lockKey(name);
    boolean decided = false; // lock outcome known: never run the job twice
    try (Connection connection = dataSource.getConnection()) {
      if (!tryLock(connection, key)) {
        decided = true;
        log.info("Scheduled job {} skipped: already running on another instance", name);
        return;
      }
      decided = true;
      try {
        job.run();
      } finally {
        unlock(connection, key);
      }
    } catch (java.sql.SQLException e) {
      if (decided) {
        // Only closing the connection failed; the job already ran (or was skipped).
        log.warn("Scheduled job {}: closing the lock connection failed: {}", name, e.getClass().getSimpleName());
        return;
      }
      // Fail open: a lock-infrastructure problem must not silently stop
      // payroll delivery or GDPR deletions; the jobs are idempotent per row.
      log.warn("Scheduled job {}: advisory lock unavailable ({}), running unlocked", name,
          e.getClass().getSimpleName());
      job.run();
    }
  }

  /** Stable 64-bit key per job name (String.hashCode is specified, so stable across JVMs). */
  static long lockKey(String name) {
    return 0x4E46_0000_0000_0000L | (name.hashCode() & 0xFFFF_FFFFL);
  }

  private static boolean tryLock(Connection connection, long key) throws java.sql.SQLException {
    try (PreparedStatement statement = connection.prepareStatement("select pg_try_advisory_lock(?)")) {
      statement.setLong(1, key);
      try (ResultSet result = statement.executeQuery()) {
        return result.next() && result.getBoolean(1);
      }
    }
  }

  private static void unlock(Connection connection, long key) {
    try (PreparedStatement statement = connection.prepareStatement("select pg_advisory_unlock(?)")) {
      statement.setLong(1, key);
      statement.execute();
    } catch (java.sql.SQLException e) {
      // The lock dies with the session anyway when the pool closes the connection.
      log.warn("Could not release advisory lock {}: {}", key, e.getClass().getSimpleName());
    }
  }
}
