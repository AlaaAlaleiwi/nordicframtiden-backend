package com.nordicframtiden.api;

import com.nordicframtiden.security.jwt.JwtService;
import com.nordicframtiden.security.model.AppUser;
import com.nordicframtiden.security.model.Permission;
import com.nordicframtiden.security.model.Role;
import com.nordicframtiden.security.repo.AppUserRepository;
import com.nordicframtiden.security.repo.UserProfileRepository;
import com.nordicframtiden.admin.model.AdminProfileRepository;
import com.nordicframtiden.security.service.PasswordResetService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final AuthenticationManager authManager;
    private final JwtService jwtService;
    private final AppUserRepository userRepo;
    private final UserProfileRepository userProfiles;
    private final AdminProfileRepository adminProfiles;
    private final PasswordResetService passwordResetService;

    public AuthController(AuthenticationManager authManager,
                          JwtService jwtService,
                          AppUserRepository userRepo,
                          UserProfileRepository userProfiles,
                          AdminProfileRepository adminProfiles,
                          PasswordResetService passwordResetService) {
        this.authManager = authManager;
        this.jwtService = jwtService;
        this.userRepo = userRepo;
        this.userProfiles = userProfiles;
        this.adminProfiles = adminProfiles;
        this.passwordResetService = passwordResetService;
    }

    // ---------- DTOs ----------
    record LoginRequest(String username, String password) {}

    // include perms so frontend can render menus immediately
    record LoginResponse(String accessToken, String refreshToken, List<String> roles, List<String> perms) {}

    record RefreshRequest(String refreshToken) {}

    record MeResponse(Long id, String username, String fullName, List<String> roles, List<String> perms,
                       Long photoId, String photoUpdatedAt) {}

    // ---------- Endpoints ----------
    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest req) {
        // Authenticate credentials. Never include the submitted password or username in logs.
        try {
            authManager.authenticate(
                    new UsernamePasswordAuthenticationToken(req.username(), req.password()));
        } catch (org.springframework.security.core.AuthenticationException exception) {
            log.warn("Login attempt rejected");
            throw exception;
        }
        log.info("Login succeeded");
        // At this point authentication succeeded – fetch the full user entity
        AppUser user = userRepo.findByUsername(req.username())
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        // Build JWT claims
        Map<String, Object> claims = new HashMap<>();
        List<String> roleNames = user.getRoles().stream()
                .map(Role::name)
                .toList();
        List<String> permNames = user.getPermissions().stream()
                .map(Permission::name)
                .toList();
        claims.put("roles", roleNames);
        claims.put("perms", permNames);

        // Generate tokens using the correct JwtService signature
        return ResponseEntity.ok(tokens(user, roleNames, permNames));
    }

    @PostMapping("/refresh")
    public ResponseEntity<?> refresh(@RequestBody RefreshRequest request) {
        try {
            var claims = jwtService.validateRefreshToken(request.refreshToken());
            AppUser user = jwtService.currentUser(claims, userRepo)
                    .orElseThrow(() -> new IllegalArgumentException("Active user not found or token revoked"));
            List<String> roles = user.getRoles().stream().map(Role::name).toList();
            List<String> perms = user.getPermissions().stream().map(Permission::name).toList();
            return ResponseEntity.ok(tokens(user, roles, perms));
        } catch (RuntimeException error) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
    }

    /** Device-local: the client discards its tokens. */
    @PostMapping("/logout")
    public ResponseEntity<?> logout() {
        return ResponseEntity.ok().build();
    }

    /** Invalidates every access and refresh token of the caller, on all devices. */
    @PostMapping("/logout-all")
    @Transactional
    public ResponseEntity<?> logoutAll(Authentication authentication) {
        // /auth/** is permitAll, so an anonymous caller arrives here too.
        AppUser user = authentication == null || !authentication.isAuthenticated()
                ? null
                : userRepo.findByUsername(authentication.getName()).orElse(null);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        user.revokeTokens();
        userRepo.save(user);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me")
    public ResponseEntity<?> me(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        String username = authentication.getName();
        AppUser user = userRepo.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));
        List<String> roleNames = user.getRoles().stream()
                .map(Role::name)
                .toList();
        List<String> permNames = user.getPermissions().stream()
                .map(Permission::name)
                .toList();
        String fullName = userProfiles.findByUserId(user.getId()).map(profile -> profile.getFullName())
                .or(() -> adminProfiles.findByUserId(user.getId()).map(profile -> profile.getFullName()))
                .filter(name -> !name.isBlank())
                .orElse(username);
        Long photoId = user.getPhotoId();
        String photoUpdatedAt = user.getPhotoUpdatedAt() != null
                ? String.valueOf(user.getPhotoUpdatedAt().toEpochMilli())
                : null;
        return ResponseEntity.ok(new MeResponse(user.getId(), username, fullName, roleNames, permNames, photoId, photoUpdatedAt));
    }

    private LoginResponse tokens(AppUser user, List<String> roles, List<String> perms) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("roles", roles);
        claims.put("perms", perms);
        return new LoginResponse(
                jwtService.generateAccessToken(user, claims),
                jwtService.generateRefreshToken(user),
                roles,
                perms
        );
    }

    // ---------- Password reset (self-service, public) ----------

    record ForgotPasswordRequest(String email) {}

    /** Always answers 200 so the endpoint cannot be used to enumerate accounts. */
    @PostMapping("/forgot-password")
    public ResponseEntity<?> forgotPassword(@RequestBody ForgotPasswordRequest request) {
        // Asynchronous: the response time must not reveal whether the email
        // is registered. Failures are logged (without PII) by the worker.
        passwordResetService.requestResetInBackground(request == null ? null : request.email());
        return ResponseEntity.ok(Map.of("message",
            "Om mejladressen är registrerad har ett mejl med återställningslänk skickats."));
    }

    @GetMapping("/reset-password/validate")
    public ResponseEntity<?> validateResetToken(@RequestParam("token") String token) {
        boolean valid = passwordResetService.isValid(token);
        return valid ? ResponseEntity.ok().build() : ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
    }

    record ResetPasswordRequest(String token, String newPassword) {}

    @PostMapping("/reset-password")
    public ResponseEntity<?> resetPassword(@RequestBody ResetPasswordRequest request) {
        try {
            passwordResetService.resetPassword(request.token(), request.newPassword());
            return ResponseEntity.ok(Map.of("message", "Lösenordet har uppdaterats."));
        } catch (IllegalArgumentException error) {
            return ResponseEntity.badRequest().body(Map.of("error", error.getMessage()));
        }
    }

    /**
     * Self-hosted Swedish reset page: the email link opens this directly,
     * so the flow works even without the web frontend.
     */
    @GetMapping(value = "/reset-password", produces = "text/html;charset=UTF-8")
    @ResponseBody
    public String resetPasswordPage(@RequestParam("token") String token) {
        boolean valid = passwordResetService.isValid(token);
        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html><html lang=\"sv\"><head><meta charset=\"utf-8\">")
            .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
            .append("<title>Återställ lösenord – Nordic Framtiden</title><style>")
            .append("*{box-sizing:border-box}body{margin:0;padding:24px 12px;background:#f4f6f8;")
            .append("font-family:'Helvetica Neue',Helvetica,Arial,sans-serif;color:#1f2933;}")
            .append(".card{max-width:420px;margin:0 auto;background:#fff;border-radius:12px;overflow:hidden;")
            .append("box-shadow:0 1px 4px rgba(16,24,40,.08)}")
            .append(".brand{background:#0f5132;color:#fff;font-size:20px;font-weight:700;padding:24px 32px;letter-spacing:.5px}")
            .append(".body{padding:32px}")
            .append("h1{font-size:21px;margin:0 0 16px;color:#101828}")
            .append("p{font-size:14px;line-height:1.6;margin:0 0 14px}")
            .append("label{display:block;font-size:12px;font-weight:700;color:#475467;margin:14px 0 6px}")
            .append("input{width:100%;padding:11px 12px;border:1px solid #d0d5dd;border-radius:8px;font-size:15px}")
            .append("input:focus{outline:2px solid #0f5132;border-color:#0f5132}")
            .append("button{width:100%;margin-top:20px;padding:13px;border:0;border-radius:8px;")
            .append("background:#0f5132;color:#fff;font-size:15px;font-weight:600;cursor:pointer}")
            .append("button:disabled{background:#98a2b3;cursor:default}")
            .append(".msg{margin-top:14px;padding:11px 13px;border-radius:8px;font-size:13px;line-height:1.5;display:none}")
            .append(".err{background:#fef3f2;color:#b42318;display:block}")
            .append(".ok{background:#ecfdf3;color:#067647;display:block}")
            .append(".hint{font-size:12px;color:#667085;margin-top:6px}")
            .append("</style></head><body><div class=\"card\"><div class=\"brand\">Nordic Framtiden</div>")
            .append("<div class=\"body\">");

        if (!valid) {
            html.append("<h1>Länken är ogiltig</h1>")
                .append("<p>Den här återställningslänken är felaktig, har redan använts eller har utgått.")
                .append(" Begär gärna en ny via appens inloggningssida.</p>");
        } else {
            html.append("<h1>Välj ett nytt lösenord</h1>")
                .append("<p>Ange ditt nya lösenord två gånger. Efteråt loggar du in i appen med det nya lösenordet.</p>")
                .append("<form id=\"f\">")
                .append("<input type=\"hidden\" id=\"t\" value=\"")
                .append(token.replace("\"", "&quot;").replace("<", "&lt;")).append("\">")
                .append("<label for=\"p1\">Nytt lösenord</label>")
                .append("<input type=\"password\" id=\"p1\" autocomplete=\"new-password\" minlength=\"8\" required>")
                .append("<div class=\"hint\">Minst 8 tecken, med både bokstäver och siffror.</div>")
                .append("<label for=\"p2\">Upprepa nytt lösenord</label>")
                .append("<input type=\"password\" id=\"p2\" autocomplete=\"new-password\" minlength=\"8\" required>")
                .append("<button type=\"submit\" id=\"b\">Spara nytt lösenord</button>")
                .append("</form><div class=\"msg\" id=\"m\"></div>");
        }

        html.append("</div></div>");
        if (valid) {
            html.append("<script>document.getElementById('f').addEventListener('submit',async e=>{")
                .append("e.preventDefault();const b=document.getElementById('b');b.disabled=true;")
                .append("const m=document.getElementById('m');m.className='msg';")
                .append("const p1=document.getElementById('p1').value,p2=document.getElementById('p2').value;")
                .append("if(p1!==p2){m.textContent='Lösenorden matchar inte. Försök igen.';m.className='msg err';b.disabled=false;return;}")
                .append("try{const r=await fetch('/auth/reset-password',{method:'POST',headers:{'Content-Type':'application/json'},")
                .append("body:JSON.stringify({token:document.getElementById('t').value,newPassword:p1})});")
                .append("if(r.ok){m.textContent='Klart! Ditt lösenord har uppdaterats. Du kan nu logga in i appen med det nya lösenordet.';m.className='msg ok';")
                .append("document.getElementById('f').style.display='none';}")
                .append("else{const d=await r.json().catch(()=>({}));m.textContent=d.error||'Något gick fel. Försök igen.';m.className='msg err';b.disabled=false;}}catch(x){")
                .append("m.textContent='Nätverksfel. Kontrollera din anslutning och försök igen.';m.className='msg err';b.disabled=false;}});</script>");
        }
        html.append("</body></html>");
        return html.toString();
    }
}
