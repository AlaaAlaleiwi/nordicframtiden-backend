package com.nordicframtiden.admin.dashboard;

import com.nordicframtiden.availability.AvailabilityRequestRepository;
import com.nordicframtiden.chat.CallHistory;
import com.nordicframtiden.chat.CallHistoryRepository;
import com.nordicframtiden.contact.ContactRequestRepository;
import com.nordicframtiden.documents.ProfileDocumentRepository;
import com.nordicframtiden.gdpr.GdprDeletionRequestRepository;
import com.nordicframtiden.pharmacy.Pharmacy;
import com.nordicframtiden.pharmacy.PharmacyRepository;
import com.nordicframtiden.pharmacy.ScheduleShift;
import com.nordicframtiden.pharmacy.ScheduleShiftRepository;
import com.nordicframtiden.security.model.AppUser;
import com.nordicframtiden.security.repo.AppUserRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

class AdminDashboardKpiServiceTest {
    private final AppUserRepository users = mock(AppUserRepository.class);
    private final PharmacyRepository pharmacies = mock(PharmacyRepository.class);
    private final ScheduleShiftRepository shifts = mock(ScheduleShiftRepository.class);
    private final ContactRequestRepository contacts = mock(ContactRequestRepository.class);
    private final CallHistoryRepository calls = mock(CallHistoryRepository.class);
    private final AdminDashboardKpiService service = new AdminDashboardKpiService(
            users, pharmacies, shifts, contacts, mock(AvailabilityRequestRepository.class), calls,
            mock(ProfileDocumentRepository.class), mock(GdprDeletionRequestRepository.class));

    @Test
    void reusesEachWindowAndCountWithoutChangingAggregateValues() {
        var current = shift(1L, 2L, 8, "800");
        var previous = shift(1L, 2L, 4, "400");
        when(shifts.findInRange(any(), any(), isNull(), isNull()))
                .thenReturn(List.of(current), List.of(previous));
        var completed = mock(CallHistory.class);
        when(completed.getOutcome()).thenReturn("COMPLETED");
        when(completed.isVideo()).thenReturn(true);
        when(completed.getStartedAt()).thenReturn(Instant.parse("2026-01-01T10:00:00Z"));
        when(completed.getEndedAt()).thenReturn(Instant.parse("2026-01-01T10:10:00Z"));
        var missed = mock(CallHistory.class);
        when(missed.getOutcome()).thenReturn("MISSED");
        when(calls.findByStartedAtBetween(any(), any())).thenReturn(List.of(completed, missed));
        when(users.count()).thenReturn(10L);
        when(users.countByEnabledTrue()).thenReturn(8L);
        when(contacts.count()).thenReturn(6L);
        when(contacts.countByHandledFalse()).thenReturn(2L);

        var response = service.compute("MONTH");

        assertEquals(8.0, response.kpis().get("schedule.scheduledHours").value());
        assertEquals(4.0, response.kpis().get("schedule.scheduledHours").previousValue());
        assertEquals(800.0, response.kpis().get("salary.totalMonthlyCost").value());
        assertEquals(100.0, response.kpis().get("salary.avgCostPerHour").value());
        assertEquals(1.0, response.kpis().get("schedule.scheduledPharmacists").value());
        assertEquals(1.0, response.kpis().get("schedule.uniquePharmacies").value());
        assertEquals(0.8, response.kpis().get("people.activeRatio").value());
        assertEquals(4.0 / 6.0, response.kpis().get("contacts.handledRatio").value());
        assertEquals(2.0, response.kpis().get("calls.total").value());
        assertEquals(1.0, response.kpis().get("calls.completed").value());
        assertEquals(1.0, response.kpis().get("calls.missed").value());
        assertEquals(10.0, response.kpis().get("calls.totalMinutes").value());
        assertEquals(1.0, response.kpis().get("calls.videoCount").value());
        verify(shifts, times(2)).findInRange(any(), any(), isNull(), isNull());
        verify(calls, times(1)).findByStartedAtBetween(any(), any());
        verify(users, times(1)).count();
        verify(contacts, times(1)).count();
        verify(contacts, times(1)).countByHandledFalse();
    }

    @Test
    void eachNewRequestReadsFreshData() {
        when(users.count()).thenReturn(10L, 11L);
        assertEquals(10.0, service.compute("WEEK").kpis().get("people.totalUsers").value());
        assertEquals(11.0, service.compute("WEEK").kpis().get("people.totalUsers").value());
        verify(users, times(2)).count();
        verify(shifts, times(4)).findInRange(any(), any(), isNull(), isNull());
        verify(calls, times(2)).findByStartedAtBetween(any(), any());
    }

    private ScheduleShift shift(long userID, long pharmacyID, int hours, String cost) {
        var shift = mock(ScheduleShift.class);
        var user = mock(AppUser.class);
        var pharmacy = mock(Pharmacy.class);
        when(user.getId()).thenReturn(userID);
        when(pharmacy.getId()).thenReturn(pharmacyID);
        when(shift.getUser()).thenReturn(user);
        when(shift.getPharmacy()).thenReturn(pharmacy);
        var start = OffsetDateTime.parse("2026-01-01T10:00:00+01:00");
        when(shift.getStartAt()).thenReturn(start);
        when(shift.getEndAt()).thenReturn(start.plusHours(hours));
        when(shift.getHourlyCostSnapshot()).thenReturn(new BigDecimal(cost));
        return shift;
    }
}
