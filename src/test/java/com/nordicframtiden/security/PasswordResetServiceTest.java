package com.nordicframtiden.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nordicframtiden.admin.model.AdminProfileRepository;
import com.nordicframtiden.security.model.AppUser;
import com.nordicframtiden.security.model.UserProfile;
import com.nordicframtiden.security.repo.AppUserRepository;
import com.nordicframtiden.security.repo.PasswordResetTokenRepository;
import com.nordicframtiden.security.repo.UserProfileRepository;
import com.nordicframtiden.security.service.PasswordResetService;
import com.nordicframtiden.settings.EmailService;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

class PasswordResetServiceTest {

  private final AppUserRepository users = mock(AppUserRepository.class);
  private final UserProfileRepository profiles = mock(UserProfileRepository.class);
  private final PasswordResetTokenRepository tokens = mock(PasswordResetTokenRepository.class);
  private final EmailService email = mock(EmailService.class);
  private PasswordResetService service;
  private AppUser anna;

  @BeforeEach
  void setUp() {
    PlatformTransactionManager noOp = new PlatformTransactionManager() {
      @Override public TransactionStatus getTransaction(TransactionDefinition definition) {
        return new SimpleTransactionStatus();
      }
      @Override public void commit(TransactionStatus status) { }
      @Override public void rollback(TransactionStatus status) { }
    };
    service = new PasswordResetService(users, profiles, mock(AdminProfileRepository.class), tokens,
        email, null, new TransactionTemplate(noOp));
    anna = new AppUser();
    anna.setId(7L);
    anna.setUsername("anna.svensson");
    UserProfile profile = new UserProfile();
    profile.setUser(anna);
    profile.setEmail("anna@example.com");
    when(users.findById(7L)).thenReturn(Optional.of(anna));
    when(profiles.findByUserId(7L)).thenReturn(Optional.of(profile));
    when(profiles.findByEmailIgnoreCase(anyString())).thenReturn(Optional.of(profile));
  }

  @Test
  void adminResetReportsFailureAndDiscardsTheTokenWhenMailIsDisabled() {
    when(email.sendPasswordResetLink(anyString(), anyString(), anyString(), anyLong())).thenReturn(false);

    assertThat(service.adminReset(7L)).isFalse();
    // createToken clears old tokens once; the failed send clears the new one.
    verify(tokens, org.mockito.Mockito.times(2)).deleteByUserId(7L);
  }

  @Test
  void welcomeInviteReportsFailureWhenMailIsDisabled() {
    when(email.sendWelcomeEmail(anyString(), any(), anyString(), anyString(), anyLong())).thenReturn(false);

    assertThat(service.sendWelcomeInvite(7L)).isFalse();
  }

  @Test
  void selfServiceLinkGoesToTheStoredAddressNotTheTypedOne() {
    when(email.sendPasswordResetLink(anyString(), anyString(), anyString(), anyLong())).thenReturn(true);

    assertThat(service.requestReset("  ANNA@Example.com ")).isTrue();
    verify(email).sendPasswordResetLink(eq("anna@example.com"), eq("anna.svensson"), anyString(), anyLong());
  }
}
