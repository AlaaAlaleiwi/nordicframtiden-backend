package com.nordicframtiden.gdpr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.nordicframtiden.company.StaffShift;
import com.nordicframtiden.company.StaffShiftRepository;
import com.nordicframtiden.pharmacy.ScheduleShift;
import com.nordicframtiden.pharmacy.ScheduleShiftRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

/**
 * TDD for the deletion policy: while a deletion request is open, existing
 * shifts in the current and next month are kept for payroll, new shifts are
 * blocked beyond the next month, and the deletion lands at the end of the
 * month after the last shift month (Europe/Stockholm).
 */
@ExtendWith(MockitoExtension.class)
class DeletionPolicyTest {

  /** Fixed "today": 2026-09-15 in Stockholm (M = September, M+1 = October). */
  private static final Instant NOW = Instant.parse("2026-09-15T12:00:00Z");

  @Mock private GdprDeletionRequestRepository requests;
  @Mock private ScheduleShiftRepository scheduleShifts;
  @Mock private StaffShiftRepository staffShifts;

  private DeletionPolicy policy;
  private GdprDeletionRequest openRequest;

  @BeforeEach
  void setUp() {
    policy = new DeletionPolicy(
        requests, scheduleShifts, staffShifts, Clock.fixed(NOW, ZoneId.of("Europe/Stockholm")));
    openRequest = new GdprDeletionRequest(7L, "pharm", "anna@example.com", "moving abroad");
  }

  @Test
  void springCanConstructThePolicyBean() {
    try (var context = new AnnotationConfigApplicationContext()) {
      context.registerBean(GdprDeletionRequestRepository.class, () -> requests);
      context.registerBean(ScheduleShiftRepository.class, () -> scheduleShifts);
      context.registerBean(StaffShiftRepository.class, () -> staffShifts);
      context.register(DeletionPolicy.class);

      context.refresh();

      assertThat(context.getBean(DeletionPolicy.class)).isNotNull();
    }
  }

  private void withOpenRequest(boolean open) {
    when(requests.findTopByUserIdAndStatusInOrderByCreatedAtDesc(eq(7L), anyList()))
        .thenReturn(open ? Optional.of(openRequest) : Optional.empty());
  }

  private ScheduleShift scheduleShift(String startIso) {
    ScheduleShift s = new ScheduleShift();
    s.setStartAt(OffsetDateTime.parse(startIso));
    return s;
  }

  private StaffShift staffShift(String startIso) {
    StaffShift s = new StaffShift();
    s.setStartAt(OffsetDateTime.parse(startIso));
    return s;
  }

  @Test
  void noBlockWindowWithoutOpenRequest() {
    withOpenRequest(false);

    assertThat(policy.hasOpenRequest(7L)).isFalse();
    assertThat(policy.shiftBlock(7L)).isNull();
  }

  @Test
  void blockWindowCoversCurrentAndNextMonthOnly() {
    withOpenRequest(true);

    DeletionPolicy.ShiftBlock block = policy.shiftBlock(7L);
    assertThat(block.blockFrom()).isEqualTo(LocalDate.parse("2026-09-01"));
    assertThat(block.blockUntil()).isEqualTo(LocalDate.parse("2026-10-31"));
  }

  @Test
  void newShiftsInTheThirdMonthAreRefused() {
    withOpenRequest(true);

    assertThatThrownBy(() -> policy.assertShiftsAllowed(
        7L, OffsetDateTime.parse("2026-11-05T09:00:00Z")))
        .isInstanceOf(DeletionShiftBlockException.class)
        .hasMessageContaining("raderingsbegäran")
        .hasMessageContaining("2026-10-31");
  }

  @Test
  void shiftsInsideTheWindowAreStillAllowed() {
    withOpenRequest(true);

    policy.assertShiftsAllowed(7L, OffsetDateTime.parse("2026-09-20T09:00:00Z"));
    policy.assertShiftsAllowed(7L, OffsetDateTime.parse("2026-10-31T10:00:00Z"));
  }

  @Test
  void firstDayOfTheThirdMonthIsBlocked() {
    withOpenRequest(true);

    assertThatThrownBy(() -> policy.assertShiftsAllowed(
        7L, OffsetDateTime.parse("2026-11-01T00:00:00Z")))
        .isInstanceOf(DeletionShiftBlockException.class);
  }

  @Test
  void suggestedDateIsEndOfMonthAfterLastShiftMonth() {
    when(scheduleShifts.findInRange(any(), any(), isNull(), eq(7L)))
        .thenReturn(List.of(scheduleShift("2026-10-20T09:00:00Z")));
    when(staffShifts.findInRange(any(), any(), eq(7L))).thenReturn(List.of());

    assertThat(policy.suggestedDeletionDate(7L)).isEqualTo(LocalDate.parse("2026-11-30"));
  }

  @Test
  void suggestedDateUsesOctoberEndWhenOnlyThisMonthHasShifts() {
    when(scheduleShifts.findInRange(any(), any(), isNull(), eq(7L)))
        .thenReturn(List.of(scheduleShift("2026-09-05T09:00:00Z")));
    when(staffShifts.findInRange(any(), any(), eq(7L))).thenReturn(List.of());

    assertThat(policy.suggestedDeletionDate(7L)).isEqualTo(LocalDate.parse("2026-10-31"));
  }

  @Test
  void suggestedDateCoversBothShiftSystems() {
    when(scheduleShifts.findInRange(any(), any(), isNull(), eq(7L))).thenReturn(List.of());
    when(staffShifts.findInRange(any(), any(), eq(7L)))
        .thenReturn(List.of(staffShift("2026-09-10T09:00:00Z")));

    assertThat(policy.suggestedDeletionDate(7L)).isEqualTo(LocalDate.parse("2026-10-31"));
  }

  @Test
  void suggestedDateDefaultsToGraceDaysWithoutShifts() {
    when(scheduleShifts.findInRange(any(), any(), isNull(), eq(7L))).thenReturn(List.of());
    when(staffShifts.findInRange(any(), any(), eq(7L))).thenReturn(List.of());

    assertThat(policy.suggestedDeletionDate(7L)).isEqualTo(LocalDate.parse("2026-10-15"));
  }

  @Test
  void approvalCannotMoveTheDateBeforeTheSuggestion() {
    when(scheduleShifts.findInRange(any(), any(), isNull(), eq(7L)))
        .thenReturn(List.of(scheduleShift("2026-10-20T09:00:00Z")));
    when(staffShifts.findInRange(any(), any(), eq(7L))).thenReturn(List.of());

    assertThat(policy.effectiveApprovalDate(7L, LocalDate.parse("2026-10-01")))
        .isEqualTo(LocalDate.parse("2026-11-30"));
    assertThat(policy.effectiveApprovalDate(7L, LocalDate.parse("2026-12-15")))
        .isEqualTo(LocalDate.parse("2026-12-15"));
    assertThat(policy.effectiveApprovalDate(7L, null))
        .isEqualTo(LocalDate.parse("2026-11-30"));
  }

  @Test
  void directDeletionIsGuardedWhenShiftsExistThisMonth() {
    when(scheduleShifts.findInRange(any(), any(), isNull(), eq(7L)))
        .thenReturn(List.of(scheduleShift("2026-09-10T09:00:00Z")));
    lenient().when(staffShifts.findInRange(any(), any(), eq(7L))).thenReturn(List.of());

    assertThat(policy.hasShiftsInCurrentMonth(7L)).isTrue();
  }

  @Test
  void directDeletionIsAllowedWhenNoShiftsThisMonth() {
    when(scheduleShifts.findInRange(any(), any(), isNull(), eq(7L))).thenReturn(List.of());
    when(staffShifts.findInRange(any(), any(), eq(7L))).thenReturn(List.of());

    assertThat(policy.hasShiftsInCurrentMonth(7L)).isFalse();
  }

  @Test
  void infoExposesPolicyForTheUi() {
    withOpenRequest(true);
    openRequest.setScheduledDate(LocalDate.parse("2026-11-30"));
    when(scheduleShifts.findInRange(any(), any(), isNull(), eq(7L)))
        .thenReturn(List.of(scheduleShift("2026-09-10T09:00:00Z")));
    when(staffShifts.findInRange(any(), any(), eq(7L))).thenReturn(List.of());

    DeletionPolicy.Info info = policy.infoFor(7L);

    assertThat(info.userId()).isEqualTo(7L);
    assertThat(info.status()).isEqualTo("PENDING");
    assertThat(info.scheduledDate()).isEqualTo(LocalDate.parse("2026-11-30"));
    assertThat(info.suggestedDeletionDate()).isEqualTo(LocalDate.parse("2026-10-31"));
    assertThat(info.shiftsThisMonth()).isTrue();
    assertThat(info.blockFrom()).isEqualTo(LocalDate.parse("2026-09-01"));
    assertThat(info.blockUntil()).isEqualTo(LocalDate.parse("2026-10-31"));
  }

  @Test
  void infoIsNullSafeWithoutOpenRequest() {
    withOpenRequest(false);
    when(scheduleShifts.findInRange(any(), any(), isNull(), eq(7L))).thenReturn(List.of());
    when(staffShifts.findInRange(any(), any(), eq(7L))).thenReturn(List.of());

    DeletionPolicy.Info info = policy.infoFor(7L);

    assertThat(info.status()).isNull();
    assertThat(info.scheduledDate()).isNull();
    assertThat(info.shiftsThisMonth()).isFalse();
    assertThat(info.blockFrom()).isNull();
    assertThat(info.blockUntil()).isNull();
  }

  // ----- Scheduled date bounds the block; payroll is paid before deletion -----

  private OffsetDateTime monthStart(String isoDate) {
    return LocalDate.parse(isoDate).atStartOfDay(DeletionPolicy.ZONE).toOffsetDateTime();
  }

  @Test
  void approvedDeletionCapsNewShiftsAtTheLastMonthPaidBeforeIt() {
    // Deletion on Oct 31: September is paid Oct 21 (before), October is not.
    openRequest.setStatus(GdprDeletionRequest.STATUS_APPROVED);
    openRequest.setScheduledDate(LocalDate.parse("2026-10-31"));
    withOpenRequest(true);

    assertThat(policy.shiftBlock(7L).blockUntil()).isEqualTo(LocalDate.parse("2026-09-30"));
    policy.assertShiftsAllowed(7L, OffsetDateTime.parse("2026-09-28T09:00:00Z"));
    assertThatThrownBy(() -> policy.assertShiftsAllowed(7L, OffsetDateTime.parse("2026-10-05T09:00:00Z")))
        .isInstanceOf(DeletionShiftBlockException.class);
  }

  @Test
  void deletionOnOrBeforeTheReadyDateDoesNotCountThatMonthAsPaid() {
    // Oct 21 is September's ready date; deletion runs before delivery that day.
    assertThat(DeletionPolicy.lastWorkMonthPaidBefore(LocalDate.parse("2026-10-21")))
        .isEqualTo(java.time.YearMonth.of(2026, 8));
    assertThat(DeletionPolicy.lastWorkMonthPaidBefore(LocalDate.parse("2026-10-22")))
        .isEqualTo(java.time.YearMonth.of(2026, 9));
  }

  @Test
  void suggestionCoversShiftsBookedBeyondNextMonth() {
    // A December shift booked before the request was filed must be paid
    // (in January) before the account is deleted.
    when(scheduleShifts.findInRange(any(), any(), isNull(), eq(7L)))
        .thenReturn(List.of(scheduleShift("2026-12-10T08:00:00Z")));

    assertThat(policy.suggestedDeletionDate(7L)).isEqualTo(LocalDate.parse("2027-01-31"));
  }

  @Test
  void lastMonthsShiftsStayUnpaidUntilAfterTheirReadyDate() {
    // Today Sep 15: August's payslip is delivered Sep 21.
    when(scheduleShifts.findInRange(eq(monthStart("2026-09-01")), any(), isNull(), eq(7L)))
        .thenReturn(List.of());
    when(scheduleShifts.findInRange(eq(monthStart("2026-08-01")), any(), isNull(), eq(7L)))
        .thenReturn(List.of(scheduleShift("2026-08-20T08:00:00Z")));

    assertThat(policy.hasShiftsInCurrentMonth(7L)).isFalse();
    assertThat(policy.hasUnpaidShifts(7L)).isTrue();

    DeletionPolicy afterPayday = new DeletionPolicy(requests, scheduleShifts, staffShifts,
        Clock.fixed(Instant.parse("2026-09-22T12:00:00Z"), ZoneId.of("Europe/Stockholm")));
    assertThat(afterPayday.hasUnpaidShifts(7L)).isFalse();
  }
}
