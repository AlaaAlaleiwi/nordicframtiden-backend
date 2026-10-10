package com.nordicframtiden.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nordicframtiden.security.model.AppUser;
import com.nordicframtiden.security.model.Role;
import com.nordicframtiden.security.model.UserProfile;
import com.nordicframtiden.security.repo.AppUserRepository;
import com.nordicframtiden.security.repo.UserProfileRepository;
import com.nordicframtiden.security.service.UserService;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Self-service password reset sends the link to the stored email, so changing
 * another user's email is an account takeover. PERM_PEOPLE staff may manage
 * pharmacists, but only an ADMIN may change their email.
 */
class UserEmailChangeAuthorizationTest {

  private AppUserRepository userRepo;
  private UserProfileRepository profileRepo;
  private UserService service;
  private UserProfile profile;

  @BeforeEach
  void setUp() {
    userRepo = mock(AppUserRepository.class);
    profileRepo = mock(UserProfileRepository.class);
    service = new UserService(userRepo, profileRepo, null, null, null, null, null, null, null,
        null, null, null, null, null, null, null);

    AppUser pharmacist = new AppUser();
    pharmacist.setId(7L);
    pharmacist.setUsername("anna.svensson");
    pharmacist.setRoles(new HashSet<>(Set.of(Role.USER)));
    profile = new UserProfile();
    profile.setUser(pharmacist);
    profile.setFullName("Anna Svensson");
    profile.setEmail("anna@example.com");
    when(userRepo.findById(7L)).thenReturn(Optional.of(pharmacist));
    when(profileRepo.findByUserId(7L)).thenReturn(Optional.of(profile));
  }

  @AfterEach
  void clear() {
    SecurityContextHolder.clearContext();
  }

  private void loginAs(String... authorities) {
    SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
        "caller", null, List.of(authorities).stream().map(SimpleGrantedAuthority::new).toList()));
  }

  private void changeEmail(String email) {
    service.updateWithProfile(7L, null, email, null, null, null, null, null, null, null, null, null);
  }

  @Test
  void people_staff_cannot_redirect_a_pharmacists_email() {
    loginAs("ROLE_STAFF", "PERM_PEOPLE");

    assertThatThrownBy(() -> changeEmail("attacker@example.com"))
        .isInstanceOf(AccessDeniedException.class);

    assertThat(profile.getEmail()).isEqualTo("anna@example.com");
    verify(profileRepo, never()).save(profile);
  }

  @Test
  void people_staff_can_still_edit_other_fields_and_resend_the_same_email() {
    loginAs("ROLE_STAFF", "PERM_PEOPLE");

    service.updateWithProfile(7L, "Anna S", "ANNA@example.com", null, null, null, null, null,
        null, null, null, null);

    assertThat(profile.getFullName()).isEqualTo("Anna S");
    assertThat(profile.getEmail()).isEqualTo("anna@example.com");
  }

  @Test
  void admin_can_change_the_email() {
    loginAs("ROLE_ADMIN");

    changeEmail("anna.new@example.com");

    assertThat(profile.getEmail()).isEqualTo("anna.new@example.com");
  }
}
