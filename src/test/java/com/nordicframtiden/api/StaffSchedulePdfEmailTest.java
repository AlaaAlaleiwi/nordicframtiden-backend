package com.nordicframtiden.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.nordicframtiden.company.StaffScheduleService;
import com.nordicframtiden.security.model.UserProfile;
import com.nordicframtiden.security.repo.UserProfileRepository;
import com.nordicframtiden.settings.EmailService;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The company sender must only mail a schedule PDF to the employee's own stored address. */
class StaffSchedulePdfEmailTest {

  private final EmailService emailService = mock(EmailService.class);
  private final UserProfileRepository profiles = mock(UserProfileRepository.class);
  private final StaffScheduleController controller =
      new StaffScheduleController(mock(StaffScheduleService.class), emailService, profiles);

  @Test
  void callerSuppliedRecipientIsIgnoredInFavourOfTheStoredEmail() {
    UserProfile profile = new UserProfile();
    profile.setEmail("erik@company.se");
    profile.setFullName("Erik Staff");
    when(profiles.findByUserId(9L)).thenReturn(Optional.of(profile));
    when(emailService.sendSchedulePdfEmail(any(), any(), any(), any(), any(), any())).thenReturn(true);

    var response = controller.sendSchedulePdfEmail(Map.of(
        "userId", 9, "email", "outsider@example.com", "employeeName", "Fake", "pdfBase64", "cGRm"));

    verify(emailService).sendSchedulePdfEmail(eq("erik@company.se"), eq("Erik Staff"), any(), any(), any(), any());
    assertThat(response.getBody()).containsEntry("recipient", "erik@company.se");
  }

  @Test
  void employeeWithoutStoredEmailGetsNothingSent() {
    when(profiles.findByUserId(9L)).thenReturn(Optional.empty());

    var response = controller.sendSchedulePdfEmail(Map.of(
        "userId", 9, "email", "outsider@example.com", "pdfBase64", "cGRm"));

    assertThat(response.getStatusCode().value()).isEqualTo(400);
    verifyNoInteractions(emailService);
  }
}
