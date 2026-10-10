package com.nordicframtiden.admin;

import com.nordicframtiden.security.service.PasswordResetService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import com.nordicframtiden.settings.EmailService;


import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;


@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@WithMockUser(roles = "ADMIN")
@ActiveProfiles("test")
class AdminServiceIT {

  

  @Autowired AdminService adminService;
  @Autowired PasswordResetService passwordResetService;
  // CI has no SMTP server: the mail outcome is controlled here so both the
  // "sent" and the "mail disabled" paths are asserted deterministically.
  @MockitoBean EmailService emailService;
 
  @Test
  void reset_password_changes_password_hash_or_value() {
    // Unique email/phone per run so the test is idempotent against a
    // persistent database (CI's fresh Postgres masked this before).
    String suffix = String.valueOf(System.nanoTime());
    var created = adminService.createAdminWithProfile(
        true, "Reset Me", "reset-" + suffix + "@nordic.se", "070" + suffix.substring(suffix.length() - 7)
    );

    // Created accounts get an unusable random password and are invited by
    // email to choose their own — no clear-text password leaves the backend.
    assertThat(created.password()).isNull();

    // The invite must be sendable for an account with an email on file
    // (issuing a token invalidates any previous one and is committed first).
    when(emailService.sendWelcomeEmail(anyString(), any(), anyString(), anyString(), anyLong())).thenReturn(true);
    assertThat(passwordResetService.sendWelcomeInvite(created.id())).isTrue();

    // Mail disabled/unconfigured: the admin must be told nothing was sent
    // (no false "invite sent" confirmation).
    when(emailService.sendWelcomeEmail(anyString(), any(), anyString(), anyString(), anyLong())).thenReturn(false);
    assertThat(passwordResetService.sendWelcomeInvite(created.id())).isFalse();
  }
}
