package com.nordicframtiden.security.service;

import com.nordicframtiden.admin.model.AdminProfileRepository;
import com.nordicframtiden.security.model.AppUser;
import com.nordicframtiden.security.model.PasswordResetToken;
import com.nordicframtiden.security.repo.AppUserRepository;
import com.nordicframtiden.security.repo.PasswordResetTokenRepository;
import com.nordicframtiden.security.repo.UserProfileRepository;
import com.nordicframtiden.settings.EmailService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.Optional;

/**
 * Password reset flows:
 * <ul>
 *   <li>Self-service: the user enters their registered email on the login
 *       page and receives an email with a link to the reset page where a new
 *       password is chosen.</li>
 *   <li>Admin-initiated: an admin resets a user's/admin's password; the
 *       person receives an email with a personal reset link (no temporary
 *       password is sent in clear text).</li>
 * </ul>
 * Only the SHA-256 hash of each token is stored; tokens are single-use and
 * expire after {@link #TOKEN_TTL_MINUTES} minutes.
 */
@Service
public class PasswordResetService {

  private static final Logger log = LoggerFactory.getLogger(PasswordResetService.class);

  public static final int TOKEN_TTL_MINUTES = 60;

  private final AppUserRepository userRepo;
  private final UserProfileRepository userProfileRepo;
  private final AdminProfileRepository adminProfileRepo;
  private final PasswordResetTokenRepository tokenRepo;
  private final EmailService emailService;
  private final PasswordEncoder encoder;
  private final TransactionTemplate tx;
  private final SecureRandom random = new SecureRandom();

  public PasswordResetService(AppUserRepository userRepo,
                              UserProfileRepository userProfileRepo,
                              AdminProfileRepository adminProfileRepo,
                              PasswordResetTokenRepository tokenRepo,
                              EmailService emailService,
                              PasswordEncoder encoder,
                              TransactionTemplate tx) {
    this.userRepo = userRepo;
    this.userProfileRepo = userProfileRepo;
    this.adminProfileRepo = adminProfileRepo;
    this.tokenRepo = tokenRepo;
    this.emailService = emailService;
    this.encoder = encoder;
    this.tx = tx;
  }

  /**
   * Self-service request from the login page. Returns false when the email is
   * unknown or the mail could not be sent (caller still answers generically).
   * The token is committed first; a mail failure must not roll it back — the
   * token is simply cleaned up and the failure is logged.
   */
  public boolean requestReset(String email) {
    if (email == null || email.isBlank()) return false;
    AppUser user = findByEmail(email.trim());
    if (user == null) return false;
    // The link goes to the address ON FILE, never the typed one (the lookup
    // is case-insensitive and may fall back across profile tables).
    String recipient = emailOf(user);
    if ((recipient == null || recipient.isBlank()) && user.getUsername() != null
        && user.getUsername().contains("@")) {
      recipient = user.getUsername(); // email-as-username accounts: that IS the address on file
    }
    if (recipient == null || recipient.isBlank()) return false;
    String rawToken = tx.execute(status -> createToken(user));
    boolean sent;
    try {
      sent = emailService.sendPasswordResetLink(recipient, user.getUsername(), rawToken, TOKEN_TTL_MINUTES);
    } catch (RuntimeException e) {
      log.error("Could not send password reset email (recipient redacted): {}", e.getClass().getSimpleName());
      sent = false;
    }
    if (!sent) discardTokens(user);
    return sent;
  }

  /**
   * Login-page entry point. Runs on the mail pool so the HTTP response takes
   * the same time whether or not the email is registered — a synchronous
   * token write + SMTP round trip would reveal registered addresses.
   */
  @Async("mailExecutor")
  public void requestResetInBackground(String email) {
    try {
      requestReset(email);
    } catch (RuntimeException e) {
      log.error("Password reset request failed (mail not sent): {}", e.getClass().getSimpleName());
    }
  }

  /** The delete query needs a transaction; a failed send must not leave a live token. */
  private void discardTokens(AppUser user) {
    tx.executeWithoutResult(status -> tokenRepo.deleteByUserId(user.getId()));
  }

  /**
   * Welcome invite for a newly created account: sends a link so the person
   * sets their own password. Returns false when the account has no email on
   * file or mail is disabled/unconfigured; a mail failure propagates so the
   * admin sees a real error instead of a false "email sent" confirmation.
   */
  public boolean sendWelcomeInvite(Long userId) {
    AppUser user = userRepo.findById(userId)
        .orElseThrow(() -> new IllegalArgumentException("User not found"));
    String email = emailOf(user);
    if (email == null || email.isBlank()) return false;
    String rawToken = tx.execute(status -> createToken(user));
    boolean sent = emailService.sendWelcomeEmail(email, fullNameOf(user, user.getUsername()), user.getUsername(), rawToken, TOKEN_TTL_MINUTES);
    if (!sent) discardTokens(user);
    return sent;
  }

  /**
   * Admin-initiated reset: issues a reset-link email. Returns false when the
   * account has no email on file or mail is disabled/unconfigured; a mail
   * failure propagates so the admin sees an error instead of a false "email
   * sent" confirmation.
   */
  public boolean adminReset(Long userId) {
    AppUser user = userRepo.findById(userId)
        .orElseThrow(() -> new IllegalArgumentException("User not found"));
    String email = emailOf(user);
    if (email == null || email.isBlank()) return false;
    String rawToken = tx.execute(status -> createToken(user));
    boolean sent = emailService.sendPasswordResetLink(email, user.getUsername(), rawToken, TOKEN_TTL_MINUTES);
    if (!sent) discardTokens(user);
    return sent;
  }

  /** True when the token exists, is unused and unexpired. */
  @Transactional(readOnly = true)
  public boolean isValid(String rawToken) {
    return findValidToken(rawToken) != null;
  }

  /**
   * Consumes a valid token and sets the new password. The password change is
   * committed atomically with marking the token used; the confirmation email
   * is sent afterwards, outside the transaction — a mail failure must never
   * roll back a password the user just chose.
   */
  public void resetPassword(String rawToken, String newPassword) {
    AppUser user = tx.execute(status -> {
      PasswordResetToken token = findValidToken(rawToken);
      if (token == null) {
        throw new IllegalArgumentException("Ogiltig eller utgången återställningslänk.");
      }
      String problem = PasswordPolicy.problemWith(newPassword);
      if (problem != null) {
        throw new IllegalArgumentException(problem);
      }

      AppUser managed = token.getUser();
      managed.setPasswordHash(encoder.encode(newPassword));
      userRepo.save(managed);

      token.setUsedAt(OffsetDateTime.now());
      tokenRepo.save(token);
      return managed;
    });

    try {
      String email = emailOf(user);
      if (email != null && !email.isBlank()) {
        emailService.sendPasswordResetConfirmation(email, user.getUsername());
      }
    } catch (RuntimeException e) {
      log.error("Password changed but the confirmation email could not be sent: {}",
          e.getClass().getSimpleName());
    }
  }

  // ---------- helpers ----------

  private PasswordResetToken findValidToken(String rawToken) {
    if (rawToken == null || rawToken.isBlank()) return null;
    Optional<PasswordResetToken> found = tokenRepo.findByTokenHash(PasswordResetToken.hashOf(rawToken));
    if (found.isEmpty()) return null;
    PasswordResetToken token = found.get();
    if (token.isUsed() || token.getExpiresAt().isBefore(OffsetDateTime.now())) return null;
    return token;
  }

  private String createToken(AppUser user) {
    tokenRepo.deleteByUserId(user.getId());
    byte[] bytes = new byte[32];
    random.nextBytes(bytes);
    String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    PasswordResetToken token = new PasswordResetToken();
    token.setUser(user);
    token.setTokenHash(PasswordResetToken.hashOf(raw));
    token.setExpiresAt(OffsetDateTime.now().plusMinutes(TOKEN_TTL_MINUTES));
    tokenRepo.save(token);
    return raw;
  }

  /** Full name from the profile (user or admin), falling back to the given default. */
  private String fullNameOf(AppUser user, String fallback) {
    String viaUserProfile = userProfileRepo.findByUserId(user.getId()).map(p -> p.getFullName()).orElse(null);
    if (viaUserProfile != null && !viaUserProfile.isBlank()) return viaUserProfile;
    String viaAdminProfile = adminProfileRepo.findByUserId(user.getId()).map(p -> p.getFullName()).orElse(null);
    if (viaAdminProfile != null && !viaAdminProfile.isBlank()) return viaAdminProfile;
    return fallback;
  }

  private AppUser findByEmail(String email) {
    Optional<Long> viaUserProfile = userProfileRepo.findByEmailIgnoreCase(email).map(p -> p.getUser().getId());
    if (viaUserProfile.isPresent()) {
      return userRepo.findById(viaUserProfile.get()).orElse(null);
    }
    Optional<Long> viaAdminProfile = adminProfileRepo.findByEmailIgnoreCase(email).map(p -> p.getUser().getId());
    if (viaAdminProfile.isPresent()) {
      return userRepo.findById(viaAdminProfile.get()).orElse(null);
    }
    // Fallback: some accounts log in with their email as username.
    return userRepo.findByUsername(email).orElse(null);
  }

  private String emailOf(AppUser user) {
    String viaUserProfile = userProfileRepo.findByUserId(user.getId()).map(p -> p.getEmail()).orElse(null);
    if (viaUserProfile != null && !viaUserProfile.isBlank()) return viaUserProfile;
    String viaAdminProfile = adminProfileRepo.findByUserId(user.getId()).map(p -> p.getEmail()).orElse(null);
    if (viaAdminProfile != null && !viaAdminProfile.isBlank()) return viaAdminProfile;
    return null;
  }

  /** Minimal shared password policy for reset flows. */
  static final class PasswordPolicy {
    private PasswordPolicy() {}

    /** Returns null when acceptable, otherwise a Swedish error message. */
    static String problemWith(String password) {
      if (password == null || password.length() < 8) {
        return "Lösenordet måste vara minst 8 tecken.";
      }
      if (password.length() > 128) {
        return "Lösenordet får vara högst 128 tecken.";
      }
      if (!password.matches(".*[A-Za-zåäöÅÄÖ].*") || !password.matches(".*[0-9].*")) {
        return "Lösenordet måste innehålla både bokstäver och siffror.";
      }
      return null;
    }
  }
}
