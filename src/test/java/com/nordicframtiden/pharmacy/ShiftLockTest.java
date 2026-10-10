package com.nordicframtiden.pharmacy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.nordicframtiden.company.StaffShift;
import com.nordicframtiden.company.StaffShiftRepository;
import com.nordicframtiden.security.model.AppUser;
import com.nordicframtiden.security.repo.AppUserRepository;
import com.nordicframtiden.security.service.UserService;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * TDD for the past-shift lock: a shift whose Stockholm start day is before
 * today can no longer be edited or deleted, and new shifts cannot be created
 * on days that have already passed. Today's shifts stay fully editable
 * (parity with web/iOS/Android UIs).
 */
@ExtendWith(MockitoExtension.class)
class ShiftLockTest {

    /** Fixed "now": 2026-09-28 10:00 Stockholm. */
    private static final Instant NOW = Instant.parse("2026-09-28T08:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneId.of("Europe/Stockholm"));

    @Mock private ScheduleShiftRepository shiftRepo;
    @Mock private PharmacyRepository pharmacyRepo;
    @Mock private AppUserRepository userRepo;
    @Mock private UserService userService;
    @Mock private StaffShiftRepository staffShiftRepo;

    private ScheduleService service;
    private com.nordicframtiden.company.StaffScheduleService staffService;

    @BeforeEach
    void setUp() {
        service = new ScheduleService(shiftRepo, pharmacyRepo, userRepo, userService, null, CLOCK);
        staffService = new com.nordicframtiden.company.StaffScheduleService(
            staffShiftRepo, userRepo, null, CLOCK);
    }

    private AppUser user() {
        AppUser u = new AppUser();
        u.setId(7L);
        u.setUsername("pharm");
        return u;
    }

    private ScheduleShift shift(String startIso, String endIso) {
        ScheduleShift s = new ScheduleShift();
        s.setUser(user());
        s.setStartAt(OffsetDateTime.parse(startIso));
        s.setEndAt(OffsetDateTime.parse(endIso));
        return s;
    }

    private StaffShift staffShift(String startIso, String endIso) {
        StaffShift s = new StaffShift();
        s.setStartAt(OffsetDateTime.parse(startIso));
        s.setEndAt(OffsetDateTime.parse(endIso));
        return s;
    }

    @Test
    void editingAPastShiftIsRefused() {
        ScheduleShift past = shift("2026-09-10T09:00:00Z", "2026-09-10T17:00:00Z");
        when(shiftRepo.findById(42L)).thenReturn(Optional.of(past));

        ShiftLockedException ex = assertThrows(ShiftLockedException.class, () ->
            service.update(42L, 1L, 7L, null, null, "new note"));

        assertTrue(ex.getMessage().contains("2026-09-10"));
        assertTrue(ex.getMessage().contains("kan inte"));
    }

    @Test
    void deletingAPastShiftIsRefused() {
        ScheduleShift past = shift("2026-09-10T09:00:00Z", "2026-09-10T17:00:00Z");
        when(shiftRepo.findById(42L)).thenReturn(Optional.of(past));

        ShiftLockedException ex = assertThrows(ShiftLockedException.class, () ->
            service.delete(42L));

        assertTrue(ex.getMessage().contains("tas bort"));
    }

    @Test
    void todaysShiftIsStillEditable() {
        // Today in Stockholm (fixed clock). 08:00Z is inside Sep 28.
        ScheduleShift today = shift("2026-09-28T08:00:00Z", "2026-09-28T16:00:00Z");
        when(shiftRepo.findById(42L)).thenReturn(Optional.of(today));
        when(shiftRepo.save(any(ScheduleShift.class))).thenAnswer(inv -> inv.getArgument(0));

        ScheduleShift updated = service.update(42L, null, null, null, null, "updated note");

        assertEquals("updated note", updated.getNote());
    }

    @Test
    void futureShiftIsStillEditable() {
        ScheduleShift future = shift("2026-10-05T09:00:00Z", "2026-10-05T17:00:00Z");
        when(shiftRepo.findById(42L)).thenReturn(Optional.of(future));
        when(userRepo.findById(7L)).thenReturn(Optional.of(user()));
        when(pharmacyRepo.findById(1L)).thenReturn(Optional.of(new Pharmacy()));
        when(shiftRepo.findByUserIdAndStartAtLessThanAndEndAtGreaterThan(any(), any(), any()))
            .thenReturn(List.of());
        when(shiftRepo.save(any(ScheduleShift.class))).thenAnswer(inv -> inv.getArgument(0));

        ScheduleShift updated = service.update(42L, 1L, 7L,
            OffsetDateTime.parse("2026-10-05T10:00:00Z"),
            OffsetDateTime.parse("2026-10-05T18:00:00Z"),
            null);

        assertEquals(OffsetDateTime.parse("2026-10-05T10:00:00Z"), updated.getStartAt());
    }

    @Test
    void movingAFutureShiftIntoThePastIsRefused() {
        ScheduleShift future = shift("2026-10-05T09:00:00Z", "2026-10-05T17:00:00Z");
        when(shiftRepo.findById(42L)).thenReturn(Optional.of(future));

        ShiftLockedException ex = assertThrows(ShiftLockedException.class, () ->
            service.update(42L, null, null,
                OffsetDateTime.parse("2026-09-20T09:00:00Z"),
                OffsetDateTime.parse("2026-09-20T17:00:00Z"), null));

        assertTrue(ex.getMessage().contains("2026-09-20"));
        assertEquals(OffsetDateTime.parse("2026-10-05T09:00:00Z"), future.getStartAt());
    }

    @Test
    void movingAFutureStaffShiftIntoThePastIsRefused() {
        StaffShift future = staffShift("2026-10-05T09:00:00Z", "2026-10-05T17:00:00Z");
        when(staffShiftRepo.findById(9L)).thenReturn(Optional.of(future));

        assertThrows(ShiftLockedException.class, () ->
            staffService.update(9L, null,
                OffsetDateTime.parse("2026-09-20T09:00:00Z"),
                OffsetDateTime.parse("2026-09-20T17:00:00Z"), null));

        assertEquals(OffsetDateTime.parse("2026-10-05T09:00:00Z"), future.getStartAt());
    }

    @Test
    void creatingAShiftOnAPastDayIsRefused() {
        AppUser u = user();
        when(userRepo.findById(7L)).thenReturn(Optional.of(u));

        ShiftLockedException ex = assertThrows(ShiftLockedException.class, () ->
            service.create(1L, 7L,
                OffsetDateTime.parse("2026-09-20T09:00:00Z"),
                OffsetDateTime.parse("2026-09-20T17:00:00Z"), null));

        assertTrue(ex.getMessage().contains("2026-09-20"));
    }

    @Test
    void creatingAShiftTodayIsAllowed() {
        AppUser u = user();
        when(userRepo.findById(7L)).thenReturn(Optional.of(u));
        when(pharmacyRepo.findById(1L)).thenReturn(Optional.of(new Pharmacy()));
        com.nordicframtiden.security.model.UserProfile profile = new com.nordicframtiden.security.model.UserProfile();
        profile.setHourlyCost(new java.math.BigDecimal("100"));
        when(userService.getProfileByUserId(7L)).thenReturn(profile);
        when(shiftRepo.findByUserIdAndStartAtLessThanAndEndAtGreaterThan(any(), any(), any()))
            .thenReturn(List.of());
        when(shiftRepo.save(any(ScheduleShift.class))).thenAnswer(inv -> inv.getArgument(0));

        ScheduleShift created = service.create(1L, 7L,
            OffsetDateTime.parse("2026-09-28T12:00:00Z"),
            OffsetDateTime.parse("2026-09-28T18:00:00Z"), null);

        assertEquals(u, created.getUser());
    }

    @Test
    void staffShiftsFollowTheSameLock() {
        // Past: update + delete refused.
        StaffShift past = staffShift("2026-09-15T09:00:00Z", "2026-09-15T17:00:00Z");
        when(staffShiftRepo.findById(9L)).thenReturn(Optional.of(past));

        assertThrows(ShiftLockedException.class, () ->
            staffService.update(9L, null, null, null, "x"));
        assertThrows(ShiftLockedException.class, () -> staffService.delete(9L));

        // Today: still editable.
        StaffShift today = staffShift("2026-09-28T07:00:00Z", "2026-09-28T15:00:00Z");
        when(staffShiftRepo.findById(10L)).thenReturn(Optional.of(today));
        when(staffShiftRepo.save(any(StaffShift.class))).thenAnswer(inv -> inv.getArgument(0));

        StaffShift updated = staffService.update(10L, null, null, null, "ok");
        assertEquals("ok", updated.getNote());

        // Creating on a past day is refused.
        assertThrows(ShiftLockedException.class, () ->
            staffService.create(7L,
                OffsetDateTime.parse("2026-09-01T09:00:00Z"),
                OffsetDateTime.parse("2026-09-01T17:00:00Z"), null));
    }

    @Test
    void partialStaffShiftUpdateKeepsTheNote() {
        StaffShift today = staffShift("2026-09-28T07:00:00Z", "2026-09-28T15:00:00Z");
        today.setNote("Opening shift");
        when(staffShiftRepo.findById(10L)).thenReturn(Optional.of(today));
        when(staffShiftRepo.save(any(StaffShift.class))).thenAnswer(inv -> inv.getArgument(0));

        StaffShift updated = staffService.update(10L, null, null,
            OffsetDateTime.parse("2026-09-28T16:00:00Z"), null);

        assertEquals("Opening shift", updated.getNote());
    }

    @Test
    void lockRuleUsesTheStockholmDay() {
        // 2026-09-28T00:30Z is still Sep 28 in Stockholm (summmer: +02:00)? No —
        // CEST is +02:00, so 00:30Z is 02:30 Sep 28: today, not locked.
        OffsetDateTime earlyToday = OffsetDateTime.parse("2026-09-27T22:30:00-08:00"); // Sep 28 06:30 CEST
        OffsetDateTime lastNight = OffsetDateTime.parse("2026-09-27T21:00:00Z");       // Sep 27 23:00 CEST

        assertTrue(ShiftLockPolicy.isLocked(lastNight, java.time.LocalDate.parse("2026-09-28")));
        assertFalse(ShiftLockPolicy.isLocked(earlyToday, java.time.LocalDate.parse("2026-09-28")));
    }
}
