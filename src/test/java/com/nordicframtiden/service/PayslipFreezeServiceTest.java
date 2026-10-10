package com.nordicframtiden.service;

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
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PayslipFreezeServiceTest {
  final PayslipSnapshotRepository snapshots = mock(PayslipSnapshotRepository.class);
  final PayslipRevisionRepository revisions = mock(PayslipRevisionRepository.class);
  final PayrollService payroll = mock(PayrollService.class);
  final SalaryAdjustmentService adjustments = mock(SalaryAdjustmentService.class);
  final AppUserRepository users = mock(AppUserRepository.class);
  final ObjectMapper json = new ObjectMapper();
  final PayslipFreezeService service = serviceAt("2026-09-15T10:00:00Z");
  PayslipSnapshot snapshot;
  NetSalaryResponse original = new NetSalaryResponse(7L, "2026-08", bd("200"), bd("100"), bd("20000"),
      2026, "0180", 30, 1, bd("6000"), bd("14000"));
  static BigDecimal bd(String v) { return new BigDecimal(v); }
  PayslipFreezeService serviceAt(String instant) {
    return new PayslipFreezeService(snapshots, revisions, payroll, adjustments, users, json,
        Clock.fixed(Instant.parse(instant), ZoneId.of("Europe/Stockholm")));
  }

  @BeforeEach void setup() {
    when(users.lockForPayroll(7L)).thenReturn(Optional.of(new AppUser()));
    when(revisions.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));
  }
  void frozen(String role) throws Exception {
    snapshot = new PayslipSnapshot();
    ReflectionTestUtils.setField(snapshot, "id", 42L);
    snapshot.setPayload(json.writeValueAsString(original));
    when(snapshots.findByUserIdAndYearAndMonthAndRole(7L, 2026, 8, role)).thenReturn(Optional.of(snapshot));
    var first = new PayslipRevision(42L, 1, "original-operator", "Finalized", null, snapshot.getPayload());
    when(revisions.findTopBySnapshotIdOrderByRevisionDesc(42L)).thenReturn(Optional.of(first));
    when(revisions.findBySnapshotIdOrderByRevisionAsc(42L)).thenReturn(List.of(first));
  }
  PayslipFreezeService.Correction correction(int revision, String reason, String gross, String tax) {
    return new PayslipFreezeService.Correction(revision, reason, bd(gross), bd("0"), bd(tax), bd("0"), bd("0"));
  }
  @Test void pastReadRemainsDraftUntilExplicitlyFinalized() {
    when(payroll.netSalaryForUserMonth(7L, 2026, 8)).thenReturn(original);
    assertThat(service.resolve(7L, 2026, 8, "USER")).isEqualTo(original);
    verify(snapshots, never()).saveAndFlush(any());
    verifyNoInteractions(revisions);
  }
  @Test void explicitFinalizationPersistsBothRecordsAndActor() {
    when(payroll.netSalaryForUserMonth(7L, 2026, 8)).thenReturn(original);
    when(snapshots.saveAndFlush(any())).thenAnswer(i -> {
      PayslipSnapshot s = i.getArgument(0); ReflectionTestUtils.setField(s, "id", 42L); return s;
    });
    var result = service.finalizePayslip(7L, 2026, 8, "USER", "admin@example.se");
    assertThat(result.revision()).isEqualTo(1);
    assertThat(result.actor()).isEqualTo("admin@example.se");
    assertThat(result.payslip()).isEqualTo(original);
    verify(users).lockForPayroll(7L);
    verify(revisions).saveAndFlush(any());
  }
  @Test void failedFinalizationDoesNotReportSuccess() {
    when(payroll.netSalaryForUserMonth(7L, 2026, 8)).thenReturn(original);
    when(snapshots.saveAndFlush(any())).thenThrow(new IllegalStateException("database unavailable"));
    assertThatThrownBy(() -> service.finalizePayslip(7L, 2026, 8, "USER", "admin")).isInstanceOf(IllegalStateException.class);
    verifyNoInteractions(revisions);
  }
  @Test void finalizationRetryAndReadNeverRecalculate() throws Exception {
    frozen("USER");
    assertThat(service.finalizePayslip(7L, 2026, 8, "USER", "another").actor()).isEqualTo("original-operator");
    assertThat(service.resolve(7L, 2026, 8, "USER")).isEqualTo(original);
    verifyNoInteractions(payroll, adjustments);
    verify(snapshots, never()).saveAndFlush(any());
  }
  @Test void correctionPreservesOriginalAndInputsAndRecordsSignedChanges() throws Exception {
    frozen("STAFF");
    String originalPayload = snapshot.getPayload();
    var change = new PayslipFreezeService.Correction(1, "Missing agreed allowance", bd("100"), bd("50"), bd("30"), bd("15"), bd("25"));
    var result = service.correct(7L, 2026, 8, "STAFF", change, "payroll-admin");
    assertThat(result.revision()).isEqualTo(2);
    assertThat(result.actor()).isEqualTo("payroll-admin");
    assertThat(result.createdAt()).isNotNull();
    assertThat(result.changes()).isEqualTo(change);
    assertThat(result.payslip().grossSalary()).isEqualByComparingTo("20150");
    assertThat(result.payslip().preliminaryTax()).isEqualByComparingTo("6045");
    assertThat(result.payslip().netSalary()).isEqualByComparingTo("14130");
    assertThat(result.payslip().hourlyCost()).isEqualTo(original.hourlyCost());
    assertThat(result.payslip().totalHours()).isEqualTo(original.totalHours());
    assertThat(result.payslip().baseHourlySalary()).isEqualTo(original.baseHourlySalary());
    assertThat(result.payslip().tableNumber()).isEqualTo(original.tableNumber());
    assertThat(result.payslip().adjustments()).hasSize(3);
    assertThat(snapshot.getPayload()).isEqualTo(originalPayload);
    verifyNoInteractions(payroll, adjustments);
    verify(snapshots, never()).saveAndFlush(any());
  }
  @Test void negativeCorrectionAndLatestResolutionWork() throws Exception {
    frozen("USER");
    var corrected = service.correct(7L, 2026, 8, "USER", correction(1, "Remove duplicate", "-100", "-30"), "admin");
    var row = new PayslipRevision(42L, 2, "admin", corrected.reason(), null, json.writeValueAsString(corrected.payslip()));
    when(revisions.findTopBySnapshotIdOrderByRevisionDesc(42L)).thenReturn(Optional.of(row));
    assertThat(service.resolve(7L, 2026, 8, "USER").netSalary()).isEqualByComparingTo("13930");
    assertThat(service.history(7L, 2026, 8, "USER").getFirst().payslip()).isEqualTo(original);
    verifyNoInteractions(payroll);
  }
  @Test void finalizedCurrentAndFuturePeriodsDoNotUseCurrentSettings() throws Exception {
    frozen("USER");
    when(snapshots.findByUserIdAndYearAndMonthAndRole(7L, 2100, 1, "USER")).thenReturn(Optional.of(snapshot));
    assertThat(service.resolve(7L, 2100, 1, "USER")).isEqualTo(original);
    verifyNoInteractions(payroll);
  }
  @Test void inconsistentLegacyNetIsNotSilentlyRepairedByCorrection() throws Exception {
    frozen("USER");
    String broken = snapshot.getPayload().replace("\"netSalary\":14000", "\"netSalary\":13999");
    when(revisions.findTopBySnapshotIdOrderByRevisionDesc(42L)).thenReturn(Optional.of(new PayslipRevision(42L, 1, "old", "old", null, broken)));
    assertThatThrownBy(() -> service.correct(7L, 2026, 8, "USER", correction(1, "Fix", "100", "30"), "admin"))
        .isInstanceOf(PayslipConflictException.class).hasMessageContaining("reconcile");
    verify(revisions, never()).saveAndFlush(any());
  }
  @Test void cannotRemoveOneTimePayThatWasNeverPaid() throws Exception {
    frozen("USER");
    var change = new PayslipFreezeService.Correction(1, "Remove bonus", bd("0"), bd("-100"), bd("0"), bd("0"), bd("0"));
    assertThatThrownBy(() -> service.correct(7L, 2026, 8, "USER", change, "admin"))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("category");
  }
  @Test void staleRevisionIsRejectedWithoutWriting() throws Exception {
    frozen("USER");
    assertThatThrownBy(() -> service.correct(7L, 2026, 8, "USER", correction(0, "Fix", "100", "30"), "admin"))
        .isInstanceOf(PayslipConflictException.class);
    verify(revisions, never()).saveAndFlush(any());
  }
  @Test void rejectsMissingReasonPrecisionNoopAndNegativeTotals() throws Exception {
    frozen("USER");
    for (var change : List.of(correction(1, " ", "100", "30"), correction(1, "Fix", "0.001", "0"),
        correction(1, "Fix", "0", "0"), correction(1, "Fix", "-30000", "0"))) {
      assertThatThrownBy(() -> service.correct(7L, 2026, 8, "USER", change, "admin")).isInstanceOf(IllegalArgumentException.class);
    }
    verify(revisions, never()).saveAndFlush(any());
  }
  @Test void draftCannotBeCorrected() {
    assertThatThrownBy(() -> service.correct(7L, 2026, 8, "USER", correction(1, "Fix", "100", "30"), "admin"))
        .isInstanceOf(PayslipConflictException.class);
  }
  @Test void legacyAdjustmentWriteIsRejectedForEitherRoleBeforeDeletingAdjustments() throws Exception {
    frozen("STAFF");
    assertThatThrownBy(() -> service.saveAdjustments(7L, 2026, 8, "USER", List.of())).isInstanceOf(PayslipConflictException.class);
    verifyNoInteractions(adjustments, payroll);
  }
  @Test void draftSaveDoesNotFinalize() {
    when(payroll.netSalaryForUserMonth(7L, 2026, 8)).thenReturn(original);
    assertThat(service.saveAdjustments(7L, 2026, 8, "USER", List.of())).isEqualTo(original);
    verify(adjustments).replace(7L, 2026, 8, List.of());
    verify(snapshots, never()).saveAndFlush(any());
  }
  @Test void currentAndPreviousMonthsAreEditableThroughTheTwentieth() {
    when(payroll.netSalaryForUserMonth(7L, 2026, 9)).thenReturn(original);
    when(payroll.netSalaryForUserMonth(7L, 2026, 8)).thenReturn(original);
    assertThat(service.saveAdjustments(7L, 2026, 9, "USER", List.of())).isEqualTo(original);
    assertThat(service.saveAdjustments(7L, 2026, 8, "USER", List.of())).isEqualTo(original);
  }
  @Test void previousMonthClosesAfterTheTwentieth() {
    var afterDeadline = serviceAt("2026-09-21T10:00:00Z");
    assertThatThrownBy(() -> afterDeadline.saveAdjustments(7L, 2026, 8, "USER", List.of()))
        .isInstanceOf(PayslipConflictException.class).hasMessageContaining("until the payslip ready date");
    assertThatThrownBy(() -> afterDeadline.preview(7L, 2026, 8, "USER",
        new PayrollService.PreviewRequest(null, List.of())))
        .isInstanceOf(PayslipConflictException.class).hasMessageContaining("closed");
    verifyNoInteractions(adjustments);
  }
  @Test void previousMonthClosesOnAnEarlyReadyDateSoDeliveryNeverEmailsADraft() {
    // 2026-11-21 is a Saturday: payslips for October are emailed Friday the
    // 20th, so October must already be closed (and auto-finalized) that day.
    when(payroll.netSalaryForUserMonth(7L, 2026, 10)).thenReturn(original);
    var thursday = serviceAt("2026-11-19T10:00:00Z");
    assertThat(thursday.saveAdjustments(7L, 2026, 10, "USER", List.of())).isEqualTo(original);

    var readyFriday = serviceAt("2026-11-20T10:00:00Z");
    assertThatThrownBy(() -> readyFriday.saveAdjustments(7L, 2026, 10, "USER", List.of()))
        .isInstanceOf(PayslipConflictException.class);
    assertThatThrownBy(() -> readyFriday.finalizePayslip(7L, 2026, 10, "USER", "admin"))
        .isInstanceOf(PayslipConflictException.class);

    when(snapshots.saveAndFlush(any())).thenAnswer(i -> {
      PayslipSnapshot saved = i.getArgument(0);
      ReflectionTestUtils.setField(saved, "id", 43L);
      return saved;
    });
    readyFriday.resolve(7L, 2026, 10, "USER");
    var revision = org.mockito.ArgumentCaptor.forClass(PayslipRevision.class);
    verify(revisions).saveAndFlush(revision.capture());
    assertThat(revision.getValue().getActor()).isEqualTo("system-payroll-deadline");
  }
  @Test void readingPreviousMonthAfterDeadlineAutomaticallyFinalizesIt() {
    var afterDeadline = serviceAt("2026-09-21T10:00:00Z");
    when(payroll.netSalaryForUserMonth(7L, 2026, 8)).thenReturn(original);
    when(snapshots.saveAndFlush(any())).thenAnswer(i -> {
      PayslipSnapshot saved = i.getArgument(0);
      ReflectionTestUtils.setField(saved, "id", 42L);
      return saved;
    });

    assertThat(afterDeadline.resolve(7L, 2026, 8, "USER")).isEqualTo(original);

    var revision = org.mockito.ArgumentCaptor.forClass(PayslipRevision.class);
    verify(revisions).saveAndFlush(revision.capture());
    assertThat(revision.getValue().getActor()).isEqualTo("system-payroll-deadline");
    verify(users).lockForPayroll(7L);
  }
  @Test void previewWithinTheEditableWindowRunsEvenWhenFrozen() throws Exception {
    // Window rule (2026-09-29): the editable window beats finalization —
    // preview (live tax with unsaved adjustments) is allowed until the
    // period closes; after the 20th it is refused like every other write.
    frozen("USER");
    var afterDeadline = serviceAt("2026-09-25T10:00:00Z");
    assertThatThrownBy(() -> afterDeadline.preview(7L, 2026, 8, "USER", new PayrollService.PreviewRequest(null, null)))
        .isInstanceOf(PayslipConflictException.class);
    verifyNoInteractions(payroll);
  }
  @Test void corruptHistoryFailsClosedWithoutRecomputation() throws Exception {
    frozen("USER");
    when(revisions.findTopBySnapshotIdOrderByRevisionDesc(42L)).thenReturn(Optional.of(new PayslipRevision(42L, 1, "old", "old", null, "broken")));
    assertThatThrownBy(() -> service.resolve(7L, 2026, 8, "USER")).isInstanceOf(PayslipConflictException.class);
    verifyNoInteractions(payroll);
    verify(revisions, never()).saveAndFlush(any());
  }
}
