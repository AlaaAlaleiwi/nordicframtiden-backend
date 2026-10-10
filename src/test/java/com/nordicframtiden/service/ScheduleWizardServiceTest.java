package com.nordicframtiden.service;

import com.nordicframtiden.availability.AvailabilityRequest;
import com.nordicframtiden.availability.AvailabilityRequestRepository;
import com.nordicframtiden.chat.ChatPushSender;
import com.nordicframtiden.chat.ChatPushSubscription;
import com.nordicframtiden.chat.ChatPushSubscriptionRepository;
import com.nordicframtiden.pharmacy.Pharmacy;
import com.nordicframtiden.pharmacy.PharmacyRepository;
import com.nordicframtiden.pharmacy.ScheduleService;
import com.nordicframtiden.pharmacy.ScheduleShift;
import com.nordicframtiden.pharmacy.ScheduleShiftRepository;
import com.nordicframtiden.pharmacy.ShiftConflictException;
import com.nordicframtiden.security.model.AppUser;
import com.nordicframtiden.security.model.Role;
import com.nordicframtiden.security.model.UserProfile;
import com.nordicframtiden.security.repo.AppUserRepository;
import com.nordicframtiden.security.repo.UserProfileRepository;
import com.nordicframtiden.settings.EmailService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ScheduleWizardServiceTest {

  private ScheduleService scheduleService;
  private ScheduleShiftRepository shiftRepo;
  private PharmacyRepository pharmacyRepo;
  private AppUserRepository userRepo;
  private UserProfileRepository profileRepo;
  private AvailabilityRequestRepository availabilityRepo;
  private SchedulePdfBuilder pdfBuilder;
  private EmailService emailService;
  private ChatPushSender pushSender;
  private ChatPushSubscriptionRepository pushSubscriptions;
  private ScheduleWizardService wizard;

  private AppUser sara;
  private AppUser ali;
  private Pharmacy pharmacy;

  @BeforeEach
  void setUp() {
    scheduleService = mock(ScheduleService.class);
    shiftRepo = mock(ScheduleShiftRepository.class);
    pharmacyRepo = mock(PharmacyRepository.class);
    userRepo = mock(AppUserRepository.class);
    profileRepo = mock(UserProfileRepository.class);
    availabilityRepo = mock(AvailabilityRequestRepository.class);
    pdfBuilder = mock(SchedulePdfBuilder.class);
    emailService = mock(EmailService.class);
    pushSender = mock(ChatPushSender.class);
    pushSubscriptions = mock(ChatPushSubscriptionRepository.class);
    wizard = new ScheduleWizardService(scheduleService, shiftRepo, pharmacyRepo, userRepo,
        profileRepo, availabilityRepo, pdfBuilder, emailService, pushSender, pushSubscriptions);

    sara = user(11L, "sara");
    ali = user(12L, "ali");
    pharmacy = new Pharmacy();
    pharmacy.setName("Apotek Kronan");

    when(userRepo.findById(11L)).thenReturn(Optional.of(sara));
    when(userRepo.findById(12L)).thenReturn(Optional.of(ali));
    when(pharmacyRepo.findById(5L)).thenReturn(Optional.of(pharmacy));
    when(profileRepo.findByUserId(11L)).thenReturn(Optional.of(profile("Sara Svensson", "sara@example.com")));
    when(profileRepo.findByUserId(12L)).thenReturn(Optional.of(profile("Ali Hassan", null)));
    when(shiftRepo.findInRange(any(), any(), any(), any())).thenReturn(List.of());
    when(availabilityRepo.findByStatusInAndOverlapping(any(), any(), any())).thenReturn(List.of());
    when(pdfBuilder.build(any(), any(), any(), any())).thenReturn("%PDF-fake".getBytes());
    when(emailService.sendSchedulePdfEmail(anyString(), anyString(), any(), any(), any(), any()))
        .thenReturn(true);
  }

  @Test
  void optionsFlagsAvailablePharmacistsFirstAndListsBookedDays() {
    when(userRepo.findAllByRole(Role.USER)).thenReturn(List.of(sara, ali));
    when(shiftRepo.findInRange(any(), any(), any(), any())).thenReturn(List.of(
        shift(ali, LocalDate.of(2026, 10, 14), LocalDate.of(2026, 10, 14))));
    AvailabilityRequest approved = availability(11L,
        LocalDate.of(2026, 10, 12), LocalDate.of(2026, 10, 16),
        AvailabilityRequest.Status.APPROVED);
    when(availabilityRepo.findByStatusInAndOverlapping(any(), any(), any()))
        .thenReturn(List.of(approved));

    var options = wizard.options("WEEK", LocalDate.of(2026, 10, 14), 5L);

    // Week: Mon 12 – Sun 18 Oct 2026
    assertEquals(LocalDate.of(2026, 10, 12), options.startDate());
    assertEquals(LocalDate.of(2026, 10, 18), options.endDate());

    assertEquals(2, options.pharmacists().size());
    var first = options.pharmacists().get(0);
    assertTrue(first.available());
    assertEquals(11L, first.userId());
    var second = options.pharmacists().get(1);
    assertFalse(second.available());

    assertEquals(1, options.conflicts().size());
    assertEquals(LocalDate.of(2026, 10, 14), options.conflicts().get(0).date());
    assertEquals(12L, options.conflicts().get(0).pharmacistId());
    assertEquals("Ali Hassan", options.conflicts().get(0).pharmacistName());
  }

  @Test
  void monthPeriodSpansFirstToLastDay() {
    when(userRepo.findAllByRole(Role.USER)).thenReturn(List.of());
    var options = wizard.options("MONTH", LocalDate.of(2026, 2, 10), 5L);
    assertEquals(LocalDate.of(2026, 2, 1), options.startDate());
    assertEquals(LocalDate.of(2026, 2, 28), options.endDate());
  }

  @Test
  void invalidPeriodIsRejected() {
    when(userRepo.findAllByRole(Role.USER)).thenReturn(List.of());
    assertThrows(IllegalArgumentException.class,
        () -> wizard.options("YEAR", LocalDate.of(2026, 10, 14), 5L));
  }

  @Test
  void confirmCreatesOneShiftPerDayWithDefaultHours() throws Exception {
    when(scheduleService.create(eq(5L), eq(11L), any(), any(), any()))
        .thenAnswer(invocation -> createdShift(sara, invocation.getArgument(2), invocation.getArgument(3)));

    var result = wizard.confirm("DAY", LocalDate.of(2026, 10, 14), 5L,
        List.of(new ScheduleWizardService.Assignment(LocalDate.of(2026, 10, 14), 11L)), true);

    assertEquals(1, result.createdCount());
    assertTrue(result.conflicts().isEmpty());
    assertNotNull(result.pdfBase64());
    verify(scheduleService).create(eq(5L), eq(11L), any(), any(), any());
  }

  @Test
  void confirmSkipsBookedDayAndStillCreatesTheRest() throws Exception {
    // The day requested is already booked for Ali: the create call conflicts.
    when(scheduleService.create(eq(5L), eq(12L), any(), any(), any()))
        .thenThrow(new ShiftConflictException("already booked"));
    when(scheduleService.create(eq(5L), eq(11L), any(), any(), any()))
        .thenAnswer(invocation -> createdShift(sara, invocation.getArgument(2), invocation.getArgument(3)));

    var result = wizard.confirm("WEEK", LocalDate.of(2026, 10, 14), 5L,
        List.of(
            new ScheduleWizardService.Assignment(LocalDate.of(2026, 10, 14), 12L),
            new ScheduleWizardService.Assignment(LocalDate.of(2026, 10, 15), 11L)), true);

    assertEquals(1, result.createdCount());
    assertEquals(1, result.conflicts().size());
    assertEquals(12L, result.conflicts().get(0).pharmacistId());
    assertTrue(result.emailSent());
    assertTrue(result.notifiedUserIds().isEmpty() || result.notifiedUserIds().size() <= 2);
  }

  @Test
  void confirmWithoutAssignmentsIsRejected() {
    assertThrows(IllegalArgumentException.class,
        () -> wizard.confirm("DAY", LocalDate.of(2026, 10, 14), 5L, List.of(), true));
  }

  @Test
  void confirmWithEmailDisabledStillReturnsPdf() throws Exception {
    when(scheduleService.create(eq(5L), eq(11L), any(), any(), any()))
        .thenAnswer(invocation -> createdShift(sara, invocation.getArgument(2), invocation.getArgument(3)));

    var result = wizard.confirm("DAY", LocalDate.of(2026, 10, 14), 5L,
        List.of(new ScheduleWizardService.Assignment(LocalDate.of(2026, 10, 14), 11L)), false);

    assertEquals(1, result.createdCount());
    assertNotNull(result.pdfBase64());
    assertFalse(result.emailSent());
    verify(emailService, never()).sendSchedulePdfEmail(anyString(), anyString(), any(), any(), any(), any());
  }

  @Test
  void notificationGoesToEveryDeviceOfScheduledPharmacists() throws Exception {
    when(scheduleService.create(eq(5L), eq(11L), any(), any(), any()))
        .thenAnswer(invocation -> createdShift(sara, invocation.getArgument(2), invocation.getArgument(3)));
    ChatPushSubscription subscription = mock(ChatPushSubscription.class);
    when(subscription.getFirebaseInstallationId()).thenReturn("installation-1");
    when(pushSubscriptions.findByUserUsernameIn(List.of("sara"))).thenReturn(List.of(subscription));

    wizard.confirm("DAY", LocalDate.of(2026, 10, 14), 5L,
        List.of(new ScheduleWizardService.Assignment(LocalDate.of(2026, 10, 14), 11L)), false);

    verify(pushSender).send(eq("installation-1"), any());
    assertEquals(List.of(11L),
        wizard.confirm("DAY", LocalDate.of(2026, 10, 14), 5L,
            List.of(new ScheduleWizardService.Assignment(LocalDate.of(2026, 10, 14), 11L)), false)
            .notifiedUserIds());
  }

  @Test
  void unavailableEmailRecipientIsSkipped() throws Exception {
    when(scheduleService.create(eq(5L), eq(12L), any(), any(), any()))
        .thenAnswer(invocation -> createdShift(ali, invocation.getArgument(2), invocation.getArgument(3)));

    wizard.confirm("DAY", LocalDate.of(2026, 10, 14), 5L,
        List.of(new ScheduleWizardService.Assignment(LocalDate.of(2026, 10, 14), 12L)), true);

    // Ali has no email on file: no email, no crash.
    verify(emailService, never()).sendSchedulePdfEmail(anyString(), anyString(), any(), any(), any(), any());
  }

  @Test
  void confirmUsesPerAssignmentTimesWhenProvided() throws Exception {
    when(scheduleService.create(eq(5L), eq(11L), any(), any(), any()))
        .thenAnswer(invocation -> createdShift(sara, invocation.getArgument(2), invocation.getArgument(3)));
    when(scheduleService.create(eq(5L), eq(12L), any(), any(), any()))
        .thenAnswer(invocation -> createdShift(ali, invocation.getArgument(2), invocation.getArgument(3)));

    var result = wizard.confirm("WEEK", LocalDate.of(2026, 10, 14), 5L,
        List.of(
            // Sara Monday 12 Oct with custom hours 08:00-12:00.
            new ScheduleWizardService.Assignment(LocalDate.of(2026, 10, 12), 11L,
                java.time.LocalTime.of(8, 0), java.time.LocalTime.of(12, 0)),
            // Ali Tuesday 13 Oct falls back to the default 09:00-17:00.
            new ScheduleWizardService.Assignment(LocalDate.of(2026, 10, 13), 12L)),
        true);

    assertEquals(2, result.createdCount());
    verify(scheduleService).create(eq(5L), eq(11L),
        eq(LocalDate.of(2026, 10, 12).atTime(java.time.LocalTime.of(8, 0))
            .atZone(java.time.ZoneId.of("Europe/Stockholm")).toOffsetDateTime()),
        eq(LocalDate.of(2026, 10, 12).atTime(java.time.LocalTime.of(12, 0))
            .atZone(java.time.ZoneId.of("Europe/Stockholm")).toOffsetDateTime()),
        any());
    verify(scheduleService).create(eq(5L), eq(12L),
        eq(LocalDate.of(2026, 10, 13).atTime(java.time.LocalTime.of(9, 0))
            .atZone(java.time.ZoneId.of("Europe/Stockholm")).toOffsetDateTime()),
        eq(LocalDate.of(2026, 10, 13).atTime(java.time.LocalTime.of(17, 0))
            .atZone(java.time.ZoneId.of("Europe/Stockholm")).toOffsetDateTime()),
        any());
  }

  @Test
  void confirmRejectsInvertedTimes() {
    assertThrows(IllegalArgumentException.class,
        () -> wizard.confirm("DAY", LocalDate.of(2026, 10, 14), 5L,
            List.of(new ScheduleWizardService.Assignment(LocalDate.of(2026, 10, 14), 11L,
                java.time.LocalTime.of(17, 0), java.time.LocalTime.of(9, 0))),
            true));
  }

  @Test
  void confirmRejectsDateOutsideChosenPeriod() throws Exception {
    // WEEK of 14 Oct 2026 is Mon 12 – Sun 18 Oct; the 19th is outside it.
    assertThrows(IllegalArgumentException.class,
        () -> wizard.confirm("WEEK", LocalDate.of(2026, 10, 14), 5L,
            List.of(
                new ScheduleWizardService.Assignment(LocalDate.of(2026, 10, 14), 11L),
                new ScheduleWizardService.Assignment(LocalDate.of(2026, 10, 19), 12L)),
            true));
    verify(scheduleService, never()).create(any(), any(), any(), any(), any());
  }

  @Test
  void confirmRejectsAssigneeWithoutPharmacistRole() throws Exception {
    AppUser staff = user(13L, "staff");
    staff.setRoles(new java.util.HashSet<>(java.util.Set.of(Role.STAFF)));
    when(userRepo.findById(13L)).thenReturn(Optional.of(staff));

    assertThrows(IllegalArgumentException.class,
        () -> wizard.confirm("WEEK", LocalDate.of(2026, 10, 14), 5L,
            List.of(
                new ScheduleWizardService.Assignment(LocalDate.of(2026, 10, 14), 11L),
                new ScheduleWizardService.Assignment(LocalDate.of(2026, 10, 15), 13L)),
            true));
    verify(scheduleService, never()).create(any(), any(), any(), any(), any());
  }

  private AppUser user(Long id, String username) {
    AppUser user = new AppUser();
    user.setId(id);
    user.setUsername(username);
    user.setRoles(new java.util.HashSet<>(java.util.Set.of(Role.USER)));
    return user;
  }

  private UserProfile profile(String fullName, String email) {
    UserProfile profile = new UserProfile();
    profile.setFullName(fullName);
    profile.setEmail(email);
    return profile;
  }

  private AvailabilityRequest availability(Long userId, LocalDate start, LocalDate end,
      AvailabilityRequest.Status status) {
    AvailabilityRequest request = new AvailabilityRequest();
    request.setUser(user(userId, "user" + userId));
    request.setType(AvailabilityRequest.Type.WEEK);
    request.setStartDate(start);
    request.setEndDate(end);
    request.setStatus(status);
    return request;
  }

  private ScheduleShift shift(AppUser user, LocalDate startDay, LocalDate endDay) {
    ScheduleShift shift = new ScheduleShift();
    shift.setUser(user);
    shift.setStartAt(OffsetDateTime.parse(startDay + "T09:00:00+02:00"));
    shift.setEndAt(OffsetDateTime.parse(endDay + "T17:00:00+01:00"));
    return shift;
  }

  private ScheduleShift createdShift(AppUser user, OffsetDateTime start, OffsetDateTime end) {
    ScheduleShift shift = new ScheduleShift();
    shift.setUser(user);
    shift.setPharmacy(pharmacy);
    shift.setStartAt(start);
    shift.setEndAt(end);
    return shift;
  }
}
