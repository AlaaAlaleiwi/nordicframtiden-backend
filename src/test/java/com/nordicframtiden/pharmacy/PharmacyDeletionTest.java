package com.nordicframtiden.pharmacy;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Deleting a pharmacy cascades its shifts, so worked (locked) history blocks it. */
class PharmacyDeletionTest {

  private final PharmacyRepository pharmacies = mock(PharmacyRepository.class);
  private final ScheduleShiftRepository shifts = mock(ScheduleShiftRepository.class);
  private final PharmacyService service = new PharmacyService(pharmacies, shifts,
      Clock.fixed(Instant.parse("2026-09-28T08:00:00Z"), ShiftLockPolicy.ZONE));
  private final Pharmacy pharmacy = new Pharmacy();

  @Test
  void pharmacyWithWorkedShiftsCannotBeDeleted() {
    when(pharmacies.findById(3L)).thenReturn(Optional.of(pharmacy));
    when(shifts.existsWorkedShiftAtPharmacy(3L, OffsetDateTime.parse("2026-09-28T00:00:00+02:00")))
        .thenReturn(true);

    assertThatThrownBy(() -> service.delete(3L)).isInstanceOf(ShiftLockedException.class);
    verify(pharmacies, never()).delete(any());
  }

  @Test
  void pharmacyWithoutHistoryCanBeDeleted() {
    when(pharmacies.findById(3L)).thenReturn(Optional.of(pharmacy));
    when(shifts.existsWorkedShiftAtPharmacy(eq(3L), any())).thenReturn(false);

    service.delete(3L);

    verify(pharmacies).delete(pharmacy);
  }
}
