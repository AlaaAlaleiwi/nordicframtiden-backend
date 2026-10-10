package com.nordicframtiden.api;

import jakarta.validation.Valid;

import com.nordicframtiden.admin.AdminService;
import com.nordicframtiden.security.repo.AppUserRepository;
import com.nordicframtiden.security.service.PasswordResetService;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import com.nordicframtiden.security.model.Role;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admins")
@PreAuthorize("hasRole('ADMIN')")
public class AdminManagementController {

    private final AdminService adminService;
    private final PasswordResetService passwordResetService;
    private final AppUserRepository repo;

    public AdminManagementController(AdminService adminService,
                                     AppUserRepository repo,
                                     PasswordResetService passwordResetService) {
        this.adminService = adminService;
        this.repo = repo;
        this.passwordResetService = passwordResetService;
    }

    /* =========================
       DTOs
       ========================= */

    // No password → generated server‑side
    public record CreateAdminRequest(
            @NotBlank String fullName,
            @NotBlank @Email String email,
            @NotBlank String phone,
            Boolean enabled
    ) {}

    public record UpdateAdminRequest(
            String username,
            String fullName,
            @Email String email,
            String phone,
            Boolean enabled
    ) {}

    public record AdminResponse(
            Long id,
            String username,
            boolean enabled,
            String fullName,
            String email,
            String phone,
            Long photoId,
            String password
    ) {}

    public record AdminStats(
            long admins,
            long users,
            long staff
    ) {}

    public record ResetPasswordResponse(
            Long id,
            String username,
            String password
    ) {}

    /* =========================
       ENDPOINTS
       ========================= */
    
    @GetMapping("/me")
    public ResponseEntity<AdminResponse> me(Authentication auth) {
        if (auth == null || !auth.isAuthenticated()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        var username = auth.getName();
        var user = adminService.getDetailedUser(username);
        return ResponseEntity.ok(new AdminResponse(
                user.id(),
                user.username(),
                user.enabled(),
                user.fullName(),
                user.email(),
                user.phone(),
                repo.findById(user.id()).map(u -> u.getPhotoId()).orElse(null),
                null
        ));
    }

    @GetMapping
    public List<AdminResponse> list() {
        return adminService.listAdminsDetailed().stream()
                .map(a -> new AdminResponse(
                        a.id(),
                        a.username(),
                        a.enabled(),
                        a.fullName(),
                        a.email(),
                        a.phone(),
                        null,
                        null
                ))
                .toList();
    }

    @PostMapping("/create")
    public ResponseEntity<?> create(@Valid @RequestBody CreateAdminRequest req) {
        boolean enabled = req.enabled() == null || req.enabled();

        var created = adminService.createAdminWithProfile(
                enabled,
                req.fullName(),
                req.email(),
                req.phone()
        );

        // The person sets their own password via the welcome-email link —
        // no clear-text password is returned or emailed.
        boolean invited = passwordResetService.sendWelcomeInvite(created.id());
        if (!invited) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                "error", "Kontot har skapats, men ingen välkomstmejl kunde skickas. Skicka en återställningslänk manuellt."));
        }

        return ResponseEntity.ok(new AdminResponse(
                created.id(),
                created.username(),
                created.enabled(),
                created.fullName(),
                created.email(),
                created.phone(),
                null,
                null
        ));
    }

    @PutMapping("/{id}")
    public AdminResponse update(@PathVariable Long id, @Valid @RequestBody UpdateAdminRequest req) {
        var updated = adminService.updateAdminWithProfile(
                id,
                req.username(),
                req.fullName(),
                req.email(),
                req.phone(),
                req.enabled()
        );

        return new AdminResponse(
                updated.id(),
                updated.username(),
                updated.enabled(),
                updated.fullName(),
                updated.email(),
                updated.phone(),
                null,
                null
        );
    }

    @PostMapping("/{id}/invite")
    public ResponseEntity<?> resendInvite(@PathVariable Long id,
                                          @RequestBody(required = false) UpdateAdminRequest ignored) {
        boolean sent = adminService.resendAdminInvite(id);
        if (!sent) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("error", "Adminen har ingen registrerad mejladress."));
        }
        return ResponseEntity.ok(Map.of("message",
            "Välkomstmejl skickat. Adminen väljer ett nytt lösenord via länken."));
    }
    @GetMapping("/stats")
  public AdminStats stats() {
    return new AdminStats(
        repo.countByRole(Role.ADMIN),
        repo.countByRole(Role.USER),
        repo.countByRole(Role.STAFF)
    );
  }

    /**
     * Grants the ADMIN role to an existing USER/STAFF account (dual role).
     * The account keeps its original role and appears in both listings.
     */
    @PostMapping("/{id}/make-admin")
    public ResponseEntity<?> makeAdmin(@PathVariable Long id) {
        adminService.setAdminRole(id, true);
        return ResponseEntity.ok(Map.of("message", "Administratörsbehörighet tilldelad."));
    }

    /** Removes the ADMIN role again; the account keeps USER/STAFF. */
    @PostMapping("/{id}/remove-admin")
    public ResponseEntity<?> removeAdmin(@PathVariable Long id) {
        try {
            adminService.setAdminRole(id, false);
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
        }
        return ResponseEntity.ok(Map.of("message", "Administratörsbehörighet borttagen."));
    }

    @PostMapping("/{id}/reset-password")
    public ResponseEntity<?> resetPassword(@PathVariable Long id) {
        boolean sent = passwordResetService.adminReset(id);
        if (!sent) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "Adminen har ingen registrerad mejladress."));
        }
        return ResponseEntity.ok(Map.of("message",
                "Återställningsmejl skickat. Adminen väljer ett nytt lösenord via länken."));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        adminService.deleteAdmin(id);
    }
}
