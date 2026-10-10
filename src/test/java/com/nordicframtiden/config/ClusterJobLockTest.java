package com.nordicframtiden.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/** One instance runs a scheduled job; the others skip it. */
class ClusterJobLockTest {

  private final DataSource dataSource = mock(DataSource.class);
  private final Connection connection = mock(Connection.class);
  private final PreparedStatement lock = mock(PreparedStatement.class);
  private final PreparedStatement unlock = mock(PreparedStatement.class);
  private final ResultSet result = mock(ResultSet.class);
  private final AtomicInteger runs = new AtomicInteger();

  private void lockReturns(boolean acquired) throws SQLException {
    when(dataSource.getConnection()).thenReturn(connection);
    when(connection.prepareStatement(contains("pg_try_advisory_lock"))).thenReturn(lock);
    when(connection.prepareStatement(contains("pg_advisory_unlock"))).thenReturn(unlock);
    when(lock.executeQuery()).thenReturn(result);
    when(result.next()).thenReturn(true);
    when(result.getBoolean(1)).thenReturn(acquired);
  }

  @Test
  void runsAndReleasesWhenTheLockIsFree() throws Exception {
    lockReturns(true);

    new ClusterJobLock(dataSource).runExclusively("payslip-delivery", runs::incrementAndGet);

    assertThat(runs).hasValue(1);
    verify(unlock).setLong(1, ClusterJobLock.lockKey("payslip-delivery"));
    verify(unlock).execute();
    verify(connection).close();
  }

  @Test
  void skipsWhenAnotherInstanceHoldsTheLock() throws Exception {
    lockReturns(false);

    new ClusterJobLock(dataSource).runExclusively("payslip-delivery", runs::incrementAndGet);

    assertThat(runs).hasValue(0);
    verify(unlock, never()).execute();
  }

  @Test
  void failsOpenWhenTheDatabaseLockIsUnavailable() throws Exception {
    when(dataSource.getConnection()).thenThrow(new SQLException("pool exhausted"));

    new ClusterJobLock(dataSource).runExclusively("gdpr-deletion", runs::incrementAndGet);

    assertThat(runs).hasValue(1);
  }

  @Test
  void aFailingConnectionCloseNeverRunsTheJobTwice() throws Exception {
    lockReturns(true);
    org.mockito.Mockito.doThrow(new SQLException("broken pipe")).when(connection).close();

    new ClusterJobLock(dataSource).runExclusively("payslip-delivery", runs::incrementAndGet);

    assertThat(runs).hasValue(1);
  }

  @Test
  void jobsGetDistinctStableKeys() {
    assertThat(ClusterJobLock.lockKey("gdpr-deletion")).isNotEqualTo(ClusterJobLock.lockKey("gdpr-export-mail"));
    assertThat(ClusterJobLock.lockKey("gdpr-deletion")).isEqualTo(ClusterJobLock.lockKey("gdpr-deletion"));
  }
}
