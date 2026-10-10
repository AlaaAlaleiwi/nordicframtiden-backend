package com.nordicframtiden.gdpr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * The nightly job runs on a scheduler thread with no authentication, yet
 * UserService.deleteUser is ADMIN-only. The job must supply a system ADMIN
 * authentication for the run — and remove it afterwards.
 */
class GdprDeletionJobTest {

  @AfterEach
  void clear() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void runs_deletions_as_system_admin_and_clears_context_afterwards() {
    GdprDeletionService service = mock(GdprDeletionService.class);
    AtomicReference<Authentication> seen = new AtomicReference<>();
    when(service.executeDue(any())).thenAnswer(inv -> {
      seen.set(SecurityContextHolder.getContext().getAuthentication());
      return 1;
    });

    new GdprDeletionJob(service).executeDueDeletions();

    Authentication auth = seen.get();
    assertThat(auth).isNotNull();
    assertThat(auth.isAuthenticated()).isTrue();
    assertThat(auth.getName()).isEqualTo(GdprDeletionJob.SYSTEM_PRINCIPAL);
    assertThat(auth.getAuthorities()).extracting(GrantedAuthority::getAuthority)
        .containsExactly("ROLE_ADMIN");
    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
  }

  @Test
  void clears_system_authentication_even_when_the_run_throws() {
    GdprDeletionService service = mock(GdprDeletionService.class);
    when(service.executeDue(any())).thenThrow(new IllegalStateException("db down"));

    try {
      new GdprDeletionJob(service).executeDueDeletions();
    } catch (IllegalStateException expected) {
      // propagated to the scheduler, which logs it
    }

    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
  }
}
