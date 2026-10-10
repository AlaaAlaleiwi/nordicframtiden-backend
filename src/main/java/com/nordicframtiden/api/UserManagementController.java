package com.nordicframtiden.api;

import jakarta.validation.Valid;

import com.nordicframtiden.security.model.Permission;
import com.nordicframtiden.security.model.Role;
import com.nordicframtiden.security.service.PasswordResetService;
import com.nordicframtiden.security.service.UserService;
import com.nordicframtiden.notification.PushNotificationService;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/users")
public class UserManagementController {

  private final UserService userService;
  private final PasswordResetService passwordResetService;
  private final PushNotificationService notifications;

  public UserManagementController(UserService userService, PasswordResetService passwordResetService,
      PushNotificationService notifications) {
    this.userService = userService;
    this.passwordResetService = passwordResetService;
    this.notifications = notifications;
  }

  // ---------- DTOs ----------

  public record UserResponse(
      Long id,
      String username,
      boolean enabled,
      String fullName,
      String email,
      String phone,
      BigDecimal hourlyCost,
      String payType,
      BigDecimal monthlySalary,
      Integer yearOfBirth,
      String countyCode,
      String municipalityCode,
      Set<Permission> permissions, // ✅ NEW
      String password, // only on create/reset
      boolean admin // dual-role flag: this account also holds the ADMIN role
  ) {}

  public record CreateUserRequest(
      @NotBlank String fullName,
      @NotBlank @Email String email,
      @NotBlank String phone,
      Boolean enabled,
      BigDecimal hourlyCost,
      String payType,
      BigDecimal monthlySalary,

      @NotNull Integer yearOfBirth,
      @NotBlank String countyCode,
      @NotBlank String municipalityCode,

      Set<Permission> permissions // ✅ NEW (used for STAFF)
  ) {}

  public record UpdateUserRequest(
      String fullName,
      @Email String email,
      String phone,
      Boolean enabled,
      BigDecimal hourlyCost,
      String payType,
      BigDecimal monthlySalary,

      Integer yearOfBirth,
      String countyCode,
      String municipalityCode,

      Set<Permission> permissions // ✅ NEW (optional; only applied to STAFF)
  ) {}

  public record UpdateOwnProfileRequest(
      String fullName,
      @Email String email,
      String phone,
      Integer yearOfBirth,
      String countyCode,
      String municipalityCode
  ) {}

  private static UserResponse toResponse(UserService.DetailedUser u, String password) {
    return new UserResponse(
        u.id(),
        u.username(),
        u.enabled(),
        u.fullName(),
        u.email(),
        u.phone(),
        u.hourlyCost(),
        u.payType(),
        u.monthlySalary(),
        u.yearOfBirth(),
        u.countyCode(),
        u.municipalityCode(),
        u.permissions(),
        password,
        u.admin()
    );
  }

  private static UserResponse toResponse(UserService.UserRow u, String password) {
    return new UserResponse(
        u.id(),
        u.username(),
        u.enabled(),
        u.fullName(),
        u.email(),
        u.phone(),
        u.hourlyCost(),
        u.payType(),
        u.monthlySalary(),
        u.yearOfBirth(),
        u.countyCode(),
        u.municipalityCode(),
        u.permissions(),
        password,
        u.admin()
    );
  }

  // ---------- Endpoints ----------

  // ✅ Anyone logged-in can call /me
  @GetMapping("/me")
  @PreAuthorize("isAuthenticated()")
  public ResponseEntity<UserResponse> me(Authentication auth) {
    if (auth == null || !auth.isAuthenticated()) {
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
    }

    var username = auth.getName();
    var user = userService.getDetailedByUsername(username);
    return ResponseEntity.ok()
        .cacheControl(org.springframework.http.CacheControl.maxAge(java.time.Duration.ofMinutes(5)).cachePrivate())
        .body(toResponse(user, null));
  }

  @PutMapping("/me")
  @PreAuthorize("isAuthenticated()")
  public UserResponse updateMe(Authentication auth, @Valid @RequestBody UpdateOwnProfileRequest req) {
    var updated = userService.updateOwnProfile(
        auth.getName(),
        req.fullName(),
        req.email(),
        req.phone(),
        req.yearOfBirth(),
        req.countyCode(),
        req.municipalityCode()
    );
    return toResponse(updated, null);
  }

  // ✅ STAFF/ADMIN can view user
  @GetMapping("/{id}")
  @PreAuthorize("@accountAuthorization.canManage(authentication, #id)")
  public UserResponse getOne(@PathVariable Long id) {
    var r = userService.getDetailedById(id);
    return toResponse(r, null);
  }

  // ✅ STAFF/ADMIN can list
  @GetMapping
  @PreAuthorize("hasRole('ADMIN') or (hasAuthority('PERM_PEOPLE') and #role == T(com.nordicframtiden.security.model.Role).USER)")
  public List<UserResponse> list(@RequestParam Role role) {
    return userService.listDetailedByRole(role).stream()
        .map(r -> toResponse(r, null))
        .toList();
  }

  // ✅ STAFF/ADMIN can create users (your UI does).
  // The person gets a welcome email and chooses their own password via the
  // link — no clear-text password is returned or emailed.
  @PostMapping
  @PreAuthorize("hasRole('ADMIN') or (hasAuthority('PERM_PEOPLE') and #role == T(com.nordicframtiden.security.model.Role).USER)")
  public ResponseEntity<?> create(@RequestParam Role role, @Valid @RequestBody CreateUserRequest req) {
    boolean enabled = req.enabled() == null || req.enabled();

    var created = userService.createWithProfile(
        role,
        enabled,
        req.fullName(),
        req.email(),
        req.phone(),
        req.hourlyCost(),
        req.payType(),
        req.monthlySalary(),
        req.yearOfBirth(),
        req.countyCode(),
        req.municipalityCode(),
        req.permissions() // ✅ new
    );

    boolean invited = passwordResetService.sendWelcomeInvite(created.id());
    if (!invited) {
      return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
          "error", "Kontot har skapats, men ingen välkomstmejl kunde skickas. Skicka en återställningslänk manuellt."));
    }
    return ResponseEntity.ok(toResponse(created, null));
  }

  // ✅ STAFF/ADMIN can update
  @PutMapping("/{id}")
  @PreAuthorize("@accountAuthorization.canManage(authentication, #id)")
  public UserResponse update(@PathVariable Long id, @Valid @RequestBody UpdateUserRequest req) {
    var updated = userService.updateWithProfile(
        id,
        req.fullName(),
        req.email(),
        req.phone(),
        req.enabled(),
        req.hourlyCost(),
        req.payType(),
        req.monthlySalary(),
        req.yearOfBirth(),
        req.countyCode(),
        req.municipalityCode(),
        req.permissions() // ✅ new
    );

    notifications.notifyUser(id, "profile.updated", "Profile updated",
        "Your account information has been changed.", Map.of("userId", id));

    return toResponse(updated, null);
  }

  // ✅ ADMIN only delete
  @DeleteMapping("/{id}")
  @PreAuthorize("hasRole('ADMIN')")
  public void delete(@PathVariable Long id) {
    userService.deleteUser(id);
  }

  // ✅ ADMIN only reset-password — sends a personal reset-link email; no
  // temporary password is returned or sent in clear text.
  @PostMapping("/{id}/reset-password")
  @PreAuthorize("hasRole('ADMIN')")
  public ResponseEntity<?> resetPassword(@PathVariable Long id) {
    boolean sent = passwordResetService.adminReset(id);
    if (!sent) {
      return ResponseEntity.status(HttpStatus.CONFLICT)
          .body(Map.of("error", "Användaren har ingen registrerad mejladress."));
    }
    return ResponseEntity.ok(Map.of("message",
        "Återställningsmejl skickat. Användaren väljer ett nytt lösenord via länken."));
  }
}
