package com.nordicframtiden.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nordicframtiden.security.model.AppUser;
import com.nordicframtiden.security.repo.AppUserRepository;
import com.nordicframtiden.service.model.*;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * TDD for the unfinalize (reopen-as-draft) operation. Guards against the
 * production incident where the pre-window-check build allowed finalizing
 * the CURRENT month, silently locking every add-field/delete/save action in
 * the apps (any existing revision = read-only, by design).
 */
class PayslipUnfinalizeTest {

  final PayslipSnapshotRepository snapshots = Mockito.mock(PayslipSnapshotRepository.class);
  final PayslipRevisionRepository revisions = Mockito.mock(PayslipRevisionRepository.class);
  final PayrollService payroll = Mockito.mock(PayrollService.class);
  final SalaryAdjustmentService adjustments = Mockito.mock(SalaryAdjustmentService.class);
  final AppUserRepository users = Mockito.mock(AppUserRepository.class);
  final ObjectMapper json = new ObjectMapper();

  PayslipFreezeService serviceAt(String instant) {
    return new PayslipFreezeService(snapshots, revisions, payroll, adjustments, users, json,
        Clock.fixed(Instant.parse(instant), ZoneId.of("Europe/Stockholm")));
  }

  @BeforeEach
  void setup() {
    when(users.lockForPayroll(7L)).thenReturn(Optional.of(new AppUser()));
  }

  @Test
  void unfinalizeDeletesSnapshotAndItsRevisionsForAWindowPeriod() {
    PayslipFreezeService service = serviceAt("2026-09-15T10:00:00Z"); // Sept 15 → Aug window open
    PayslipSnapshot snapshot = new PayslipSnapshot();
    ReflectionTestUtils.setField(snapshot, "id", 42L);
    when(snapshots.findByUserIdAndYearAndMonthAndRole(7L, 2026, 8, "USER"))
        .thenReturn(Optional.of(snapshot));

    service.unfinalize(7L, 2026, 8, "USER");

    verify(snapshots).delete(snapshot);
    // Revisions cascade in the database, but delete them explicitly so the
    // operation never depends on cascade configuration.
    verify(revisions).deleteAllBySnapshotId(42L);
  }

  @Test
  void unfinalizeRefusesClosedPeriods() {
    PayslipFreezeService service = serviceAt("2026-09-15T10:00:00Z"); // July closed (before Aug)
    assertThatThrownBy(() -> service.unfinalize(7L, 2026, 7, "USER"))
        .isInstanceOf(PayslipConflictException.class)
        .hasMessageContaining("closed");
    verify(snapshots, never()).delete(any(PayslipSnapshot.class));
  }

  @Test
  void unfinalizeIsIdempotentWhenThereIsNothingToReopen() {
    PayslipFreezeService service = serviceAt("2026-09-15T10:00:00Z");
    when(snapshots.findByUserIdAndYearAndMonthAndRole(7L, 2026, 8, "USER"))
        .thenReturn(Optional.empty());

    service.unfinalize(7L, 2026, 8, "USER");

    verify(snapshots, never()).delete(any(PayslipSnapshot.class));
  }

  @Test
  void unfinalizeRefusesEvenWhenASnapshotExistsForAClosedPeriod() {
    // Defense in depth: even with data present, a closed period never reopens.
    PayslipFreezeService service = serviceAt("2026-09-25T10:00:00Z"); // Sept 25 → Aug window shut
    PayslipSnapshot snapshot = new PayslipSnapshot();
    ReflectionTestUtils.setField(snapshot, "id", 99L);
    when(snapshots.findByUserIdAndYearAndMonthAndRole(7L, 2026, 8, "USER"))
        .thenReturn(Optional.of(snapshot));

    assertThatThrownBy(() -> service.unfinalize(7L, 2026, 8, "USER"))
        .isInstanceOf(PayslipConflictException.class);
    verify(snapshots, never()).delete(any(PayslipSnapshot.class));
  }

  // ===== saveAdjustments: the editable window beats finalization =====

  @Test
  void savingAdjustmentsReopensAPrematurelyFinalizedMonth() throws Exception {
    // The production incident: the old build allowed finalizing the CURRENT
    // month, which made every add-field/delete/save silently dead (any
    // revision = read-only). Saving must cut through the premature freeze.
    PayslipFreezeService service = serviceAt("2026-09-15T10:00:00Z"); // Aug window open
    PayslipSnapshot userSnapshot = new PayslipSnapshot();
    ReflectionTestUtils.setField(userSnapshot, "id", 42L);
    // Lookups: corrections check, reopen pass (snapshot present → deleted),
    // then the draft check.
    when(snapshots.findByUserIdAndYearAndMonthAndRole(7L, 2026, 8, "USER"))
        .thenReturn(Optional.of(userSnapshot), Optional.of(userSnapshot), Optional.empty());
    when(revisions.findTopBySnapshotIdOrderByRevisionDesc(42L))
        .thenReturn(Optional.of(new PayslipRevision(42L, 1, "admin", "Finalized", null, "{}")));
    when(snapshots.findByUserIdAndYearAndMonthAndRole(7L, 2026, 8, "STAFF"))
        .thenReturn(Optional.empty());
    when(payroll.netSalaryForUserMonth(7L, 2026, 8)).thenReturn(original());

    service.saveAdjustments(7L, 2026, 8, "USER", List.of());

    verify(revisions).deleteAllBySnapshotId(42L);
    verify(snapshots).delete(userSnapshot);
    verify(adjustments).replace(org.mockito.ArgumentMatchers.eq(7L), org.mockito.ArgumentMatchers.eq(2026),
        org.mockito.ArgumentMatchers.eq(8), any());
  }

  @Test
  void savingAdjustmentsNeverErasesCorrectionHistory() {
    // A finalized payslip with correction r2 carries deliberate audit records;
    // saving adjustments must not silently delete them.
    PayslipFreezeService service = serviceAt("2026-09-15T10:00:00Z"); // Aug window open
    PayslipSnapshot snapshot = new PayslipSnapshot();
    ReflectionTestUtils.setField(snapshot, "id", 42L);
    when(snapshots.findByUserIdAndYearAndMonthAndRole(7L, 2026, 8, "USER"))
        .thenReturn(Optional.of(snapshot));
    when(revisions.findTopBySnapshotIdOrderByRevisionDesc(42L))
        .thenReturn(Optional.of(new PayslipRevision(42L, 2, "admin", "Missed bonus", "{}", "{}")));

    assertThatThrownBy(() -> service.saveAdjustments(7L, 2026, 8, "USER", List.of()))
        .isInstanceOf(PayslipConflictException.class)
        .hasMessageContaining("correction");
    verify(snapshots, never()).delete(any(PayslipSnapshot.class));
    verify(revisions, never()).deleteAllBySnapshotId(any());
    verify(adjustments, never()).replace(any(), org.mockito.ArgumentMatchers.anyInt(),
        org.mockito.ArgumentMatchers.anyInt(), any());
  }

  @Test
  void savingAdjustmentsIsStillRefusedOnceTheWindowCloses() {
    // After the 20th the month re-freezes: no reopening, no saving.
    PayslipFreezeService service = serviceAt("2026-09-25T10:00:00Z");
    PayslipSnapshot snapshot = new PayslipSnapshot();
    ReflectionTestUtils.setField(snapshot, "id", 42L);
    when(snapshots.findByUserIdAndYearAndMonthAndRole(7L, 2026, 8, "USER"))
        .thenReturn(Optional.of(snapshot));

    assertThatThrownBy(() -> service.saveAdjustments(7L, 2026, 8, "USER", List.of()))
        .isInstanceOf(PayslipConflictException.class);
    verify(adjustments, never()).replace(any(), org.mockito.ArgumentMatchers.anyInt(),
        org.mockito.ArgumentMatchers.anyInt(), any());
    verify(snapshots, never()).delete(any(PayslipSnapshot.class));
  }

  @Test
  void savingAdjustmentsOnAPlainDraftDoesNotDeleteAnything() throws Exception {
    PayslipFreezeService service = serviceAt("2026-09-15T10:00:00Z");
    when(snapshots.findByUserIdAndYearAndMonthAndRole(org.mockito.ArgumentMatchers.eq(7L),
        org.mockito.ArgumentMatchers.eq(2026), org.mockito.ArgumentMatchers.eq(8), any()))
        .thenReturn(Optional.empty());
    when(payroll.netSalaryForUserMonth(7L, 2026, 8)).thenReturn(original());

    service.saveAdjustments(7L, 2026, 8, "USER", List.of());

    verify(snapshots, never()).delete(any(PayslipSnapshot.class));
    verify(adjustments).replace(org.mockito.ArgumentMatchers.eq(7L), org.mockito.ArgumentMatchers.eq(2026),
        org.mockito.ArgumentMatchers.eq(8), any());
  }

  private NetSalaryResponse original() {
    return new NetSalaryResponse(7L, "2026-08", new BigDecimal("200"), new BigDecimal("100"),
        new BigDecimal("20000"), 2026, "0180", 30, 1, new BigDecimal("6000"), new BigDecimal("14000"));
  }
}
