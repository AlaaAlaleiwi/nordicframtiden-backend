package com.nordicframtiden.api;

import com.nordicframtiden.notification.PushNotificationService;
import com.nordicframtiden.pharmacy.Pharmacy;
import com.nordicframtiden.pharmacy.ScheduleService;
import com.nordicframtiden.pharmacy.ScheduleShift;
import com.nordicframtiden.pharmacy.ScheduleShiftRepository;
import com.nordicframtiden.security.model.AppUser;
import com.nordicframtiden.security.repo.UserProfileRepository;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;

import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class ScheduleControllerBatchLoadingTest {

    @Test
    void scheduleListLoadsProfilesOnceForAllRows() {
        ScheduleService service = mock(ScheduleService.class);
        UserProfileRepository profiles = mock(UserProfileRepository.class);
        ScheduleShiftRepository shifts = mock(ScheduleShiftRepository.class);
        PushNotificationService notifications = mock(PushNotificationService.class);
        ScheduleController controller = new ScheduleController(service, profiles, shifts, notifications);

        var first = shift(1L, 11L, "First User");
        var second = shift(2L, 12L, "Second User");
        when(service.listRange(any(), any(), isNull(), isNull())).thenReturn(List.of(first, second));
        when(profiles.findSummariesByUserIdIn(List.of(11L, 12L))).thenReturn(List.of(
                profile(11L, "Alice"), profile(12L, "Bob")));

        var result = controller.list(OffsetDateTime.parse("2026-10-01T00:00:00Z"),
                OffsetDateTime.parse("2026-11-01T00:00:00Z"), null, null);

        assertEquals(List.of("Alice", "Bob"), result.stream().map(ScheduleController.EventDto::userLabel).toList());
        verify(profiles, times(1)).findSummariesByUserIdIn(List.of(11L, 12L));
        verify(profiles, never()).findByUserId(any());
    }

    @Test
    void emptyScheduleDoesNotIssueAnEmptyProfileQuery() {
        ScheduleService service = mock(ScheduleService.class);
        UserProfileRepository profiles = mock(UserProfileRepository.class);
        ScheduleController controller = new ScheduleController(service, profiles,
                mock(ScheduleShiftRepository.class), mock(PushNotificationService.class));
        when(service.listRange(any(), any(), isNull(), isNull())).thenReturn(List.of());

        var result = controller.list(OffsetDateTime.parse("2026-10-01T00:00:00Z"),
                OffsetDateTime.parse("2026-11-01T00:00:00Z"), null, null);

        assertEquals(List.of(), result);
        verify(profiles, never()).findSummariesByUserIdIn(any());
    }

    private static ScheduleShift shift(Long id, Long userId, String username) {
        var schedule = mock(ScheduleShift.class);
        var user = mock(AppUser.class);
        var pharmacy = mock(Pharmacy.class);
        when(schedule.getId()).thenReturn(id);
        when(schedule.getUser()).thenReturn(user);
        when(user.getId()).thenReturn(userId);
        when(user.getUsername()).thenReturn(username);
        when(schedule.getPharmacy()).thenReturn(pharmacy);
        when(pharmacy.getId()).thenReturn(5L);
        when(pharmacy.getName()).thenReturn("Central");
        when(schedule.getStartAt()).thenReturn(OffsetDateTime.parse("2026-10-05T08:00:00Z"));
        when(schedule.getEndAt()).thenReturn(OffsetDateTime.parse("2026-10-05T16:00:00Z"));
        return schedule;
    }

    private static UserProfileRepository.UserProfileSummary profile(Long userId, String name) {
        return new UserProfileRepository.UserProfileSummary() {
            @Override public Long getUserId() { return userId; }
            @Override public String getFullName() { return name; }
            @Override public java.math.BigDecimal getHourlyCost() { return null; }
            @Override public String getPayType() { return "HOURLY"; }
            @Override public java.math.BigDecimal getMonthlySalary() { return null; }
        };
    }
}
