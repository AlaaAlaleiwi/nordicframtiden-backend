package com.nordicframtiden.admin;

import com.nordicframtiden.admin.model.AdminProfile;
import com.nordicframtiden.admin.model.AdminProfileRepository;
import com.nordicframtiden.chat.CallHistoryRepository;
import com.nordicframtiden.chat.ChatMessageRepository;
import com.nordicframtiden.chat.ChatPushSubscriptionRepository;
import com.nordicframtiden.chat.ChatReactionRepository;
import com.nordicframtiden.chat.ChatRoomMemberRepository;
import com.nordicframtiden.chat.ChatRoomRepository;
import com.nordicframtiden.company.StaffShiftRepository;
import com.nordicframtiden.availability.AvailabilityRequestRepository;
import com.nordicframtiden.security.model.AppUser;
import com.nordicframtiden.security.model.Role;
import com.nordicframtiden.security.repo.AppUserRepository;
import com.nordicframtiden.security.repo.UserProfileRepository;
import com.nordicframtiden.security.service.PasswordResetService;
import com.nordicframtiden.settings.EmailService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Set;

@Service
@PreAuthorize("hasRole('ADMIN')")
public class AdminService {

    private final AppUserRepository repo;
    private final AdminProfileRepository adminProfileRepo;
    private final UserProfileRepository userProfileRepo;
    private final PasswordEncoder encoder;
    private final EmailService emailService;
    private final PasswordResetService passwordResetService;

    // Repositories holding references to app_user that lack ON DELETE CASCADE.
    // They must be cleaned before the user row can be removed.
    private final ChatMessageRepository chatMessageRepo;
    private final ChatReactionRepository chatReactionRepo;
    private final ChatRoomRepository chatRoomRepo;
    private final CallHistoryRepository callHistoryRepo;
    private final ChatPushSubscriptionRepository chatPushSubscriptionRepo;
    private final StaffShiftRepository staffShiftRepo;
    private final AvailabilityRequestRepository availabilityRequestRepo;
    private final com.nordicframtiden.documents.ProfileDocumentRepository profileDocumentRepo;
    private final com.nordicframtiden.chat.ChatAttachmentRepository chatAttachmentRepo;
    private final com.nordicframtiden.service.model.PayslipSnapshotRepository payslipSnapshotRepo;
    private final com.nordicframtiden.service.model.PayslipDeliveryRequestRepository payslipDeliveryRequestRepo;
    private final com.nordicframtiden.chat.ChatRoomMemberRepository chatRoomMemberRepo;
    private final com.nordicframtiden.security.repo.PasswordResetTokenRepository resetTokenRepo;

    public AdminService(AppUserRepository repo,
                       AdminProfileRepository adminProfileRepo,
                       UserProfileRepository userProfileRepo,
                       PasswordEncoder encoder,
                       EmailService emailService,
                       PasswordResetService passwordResetService,
                       ChatMessageRepository chatMessageRepo,
                       ChatReactionRepository chatReactionRepo,
                       ChatRoomRepository chatRoomRepo,
                       CallHistoryRepository callHistoryRepo,
                       ChatPushSubscriptionRepository chatPushSubscriptionRepo,
                       StaffShiftRepository staffShiftRepo,
                       AvailabilityRequestRepository availabilityRequestRepo,
                       com.nordicframtiden.documents.ProfileDocumentRepository profileDocumentRepo,
                       com.nordicframtiden.chat.ChatAttachmentRepository chatAttachmentRepo,
                       com.nordicframtiden.service.model.PayslipSnapshotRepository payslipSnapshotRepo,
                       com.nordicframtiden.service.model.PayslipDeliveryRequestRepository payslipDeliveryRequestRepo,
                       com.nordicframtiden.chat.ChatRoomMemberRepository chatRoomMemberRepo,
                       com.nordicframtiden.security.repo.PasswordResetTokenRepository resetTokenRepo) {
        this.repo = repo;
        this.adminProfileRepo = adminProfileRepo;
        this.userProfileRepo = userProfileRepo;
        this.encoder = encoder;
        this.emailService = emailService;
        this.passwordResetService = passwordResetService;
        this.chatMessageRepo = chatMessageRepo;
        this.chatReactionRepo = chatReactionRepo;
        this.chatRoomRepo = chatRoomRepo;
        this.callHistoryRepo = callHistoryRepo;
        this.chatPushSubscriptionRepo = chatPushSubscriptionRepo;
        this.staffShiftRepo = staffShiftRepo;
        this.availabilityRequestRepo = availabilityRequestRepo;
        this.profileDocumentRepo = profileDocumentRepo;
        this.chatAttachmentRepo = chatAttachmentRepo;
        this.payslipSnapshotRepo = payslipSnapshotRepo;
        this.payslipDeliveryRequestRepo = payslipDeliveryRequestRepo;
        this.chatRoomMemberRepo = chatRoomMemberRepo;
        this.resetTokenRepo = resetTokenRepo;
    }

    /**
     * Password reset resolves an email case-insensitively across admin AND
     * user profiles and needs exactly one match: refuse duplicates the same way.
     */
    private boolean emailTaken(String email) {
        if (email == null) return false;
        String trimmed = email.trim();
        return adminProfileRepo.findByEmailIgnoreCase(trimmed).isPresent()
            || userProfileRepo.findByEmailIgnoreCase(trimmed).isPresent();
    }

    private com.nordicframtiden.gdpr.DeletionPolicy deletionPolicy;

    /** Optional so the many unit-test constructions stay unchanged. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setDeletionPolicy(com.nordicframtiden.gdpr.DeletionPolicy deletionPolicy) {
        this.deletionPolicy = deletionPolicy;
    }

    public record AdminRow(
            Long id,
            String username,
            boolean enabled,
            String fullName,
            String email,
            String phone,
            String password) {
    }

    /* =========================
       LIST (with profile fields)
       ========================= */
    @Transactional(readOnly = true)
    public List<AdminRow> listAdminsDetailed() {
        return repo.findAllAdmins().stream()
                .map(u -> {
                    AdminProfile p = adminProfileRepo.findByUserId(u.getId()).orElse(null);
                    if (p != null) {
                        return new AdminRow(
                                u.getId(),
                                u.getUsername(),
                                u.isEnabled(),
                                p.getFullName(),
                                p.getEmail(),
                                p.getPhone(),
                                null);
                    }
                    // Dual-role accounts (e.g. pharmacist + admin) have a user
                    // profile instead of an admin profile.
                    com.nordicframtiden.security.model.UserProfile up =
                        userProfileRepo.findByUserId(u.getId()).orElse(null);
                    return new AdminRow(
                            u.getId(),
                            u.getUsername(),
                            u.isEnabled(),
                            up != null ? up.getFullName() : null,
                            up != null ? up.getEmail() : null,
                            up != null ? up.getPhone() : null,
                            null);
                })
                .toList();
    }

    /* =========================
       PROMOTE / DEMOTE (dual roles)
       Grants or removes the ADMIN role on an existing USER/STAFF account.
       The account keeps its original role, so one login holds both.
       ========================= */
    @Transactional
    public void setAdminRole(Long userId, boolean admin) {
        AppUser user = repo.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));
        boolean isAdmin = user.getRoles() != null && user.getRoles().contains(Role.ADMIN);
        if (admin == isAdmin) return; // idempotent
        if (admin) {
            user.getRoles().add(Role.ADMIN);
        } else {
            // A pure admin has no other role: removing ADMIN would leave a
            // logged-in account with no role at all. Delete it instead.
            if (user.getRoles().size() <= 1) {
                throw new IllegalStateException(
                    "Kontot har ingen annan roll än administratör. Ta bort kontot i stället.");
            }
            // Never strip the last remaining admin — the workspace needs one.
            repo.lockAllAdmins();
            long adminCount = repo.countByRole(Role.ADMIN);
            if (adminCount <= 1) {
                throw new IllegalStateException("Kan inte ta bort den sista administratören.");
            }
            user.getRoles().remove(Role.ADMIN);
        }
        repo.save(user);
    }

    /* =========================
       CREATE (password generated)
       ========================= */
    @Transactional
    public AdminRow createAdminWithProfile(
            boolean enabled,
            String fullName,
            String email,
            String phone) {

        if (emailTaken(email))
            throw new IllegalArgumentException("Email already exists");

        if (adminProfileRepo.existsByPhone(phone.trim()))
            throw new IllegalArgumentException("Phone already exists");

        // 1️⃣ Generate username. The password is an unusable random value —
        // the person sets their own via the welcome-email invite link.
        String username = generateUniqueUsername(fullName);
        String password = generatePassword();

        // 2️⃣ Create login user
        AppUser user = new AppUser();
        user.setUsername(username);
        user.setPasswordHash(encoder.encode(password));
        user.setEnabled(enabled);
        // Assign the ADMIN role (the missing/unfinished code)
        user.setRoles(Set.of(Role.ADMIN));

        repo.save(user);

        // 3️⃣ Create profile linked to the user
        AdminProfile profile = new AdminProfile();
        profile.setFullName(fullName);
        profile.setEmail(email);
        profile.setPhone(phone.trim());
        profile.setUser(user);
        adminProfileRepo.save(profile);

        return new AdminRow(
                user.getId(),
                user.getUsername(),
                user.isEnabled(),
                fullName,
                email,
                phone,
                null);
    }

    /* =========================
       UPDATE (partial — nulls are ignored)
       ========================= */
    @Transactional
    public AdminRow updateAdminWithProfile(
            Long id,
            String username,
            String fullName,
            String email,
            String phone,
            Boolean enabled) {

        AppUser user = repo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));
        if (user.getRoles() == null || !user.getRoles().contains(Role.ADMIN)) {
            throw new IllegalArgumentException("User is not an admin");
        }

        if (username != null && !username.isBlank()
                && !username.equalsIgnoreCase(user.getUsername())) {
            if (repo.existsByUsername(username)) {
                throw new IllegalArgumentException("Username already exists");
            }
            user.setUsername(username.trim());
        }

        if (enabled != null) user.setEnabled(enabled);
        repo.save(user);

        // Dual-role accounts have no AdminProfile: their profile lives in the
        // user profile (same convention as listAdminsDetailed/getDetailedUser).
        AdminProfile profile = adminProfileRepo.findByUserId(id).orElse(null);
        if (profile != null) {
            if (fullName != null && !fullName.isBlank()) profile.setFullName(fullName.trim());

            if (email != null && !email.isBlank() && !email.equalsIgnoreCase(profile.getEmail())) {
                if (emailTaken(email)) {
                    throw new IllegalArgumentException("Email already exists");
                }
                profile.setEmail(email.trim());
            }

            if (phone != null && !phone.isBlank() && !phone.equals(profile.getPhone())) {
                if (adminProfileRepo.existsByPhone(phone.trim())) {
                    throw new IllegalArgumentException("Phone already exists");
                }
                profile.setPhone(phone.trim());
            }

            adminProfileRepo.save(profile);

            return new AdminRow(
                    user.getId(),
                    user.getUsername(),
                    user.isEnabled(),
                    profile.getFullName(),
                    profile.getEmail(),
                    profile.getPhone(),
                    null);
        }

        com.nordicframtiden.security.model.UserProfile userProfile =
            userProfileRepo.findByUserId(id)
                .orElseThrow(() -> new IllegalArgumentException("Profile not found"));

        if (fullName != null && !fullName.isBlank()) userProfile.setFullName(fullName.trim());

        if (email != null && !email.isBlank() && !email.equalsIgnoreCase(userProfile.getEmail())) {
            if (emailTaken(email)) {
                throw new IllegalArgumentException("Email already exists");
            }
            userProfile.setEmail(email.trim());
        }

        if (phone != null && !phone.isBlank() && !phone.equals(userProfile.getPhone())) {
            if (userProfileRepo.existsByPhone(phone.trim())) {
                throw new IllegalArgumentException("Phone already exists");
            }
            userProfile.setPhone(phone.trim());
        }

        userProfileRepo.save(userProfile);

        return new AdminRow(
                user.getId(),
                user.getUsername(),
                user.isEnabled(),
                userProfile.getFullName(),
                userProfile.getEmail(),
                userProfile.getPhone(),
                null);
    }

    /* =========================
       RESEND INVITE (set-password link by email)
       ========================= */

    /* =========================
       HELPERS
       ========================= */
    private String generateUniqueUsername(String fullName) {
        // Simple deterministic username generation – can be replaced with a more robust algorithm
        String base = fullName.toLowerCase().replaceAll("\\s+", ".");
        String candidate = base;
        int suffix = 1;
        while (repo.existsByUsername(candidate)) {
            candidate = base + suffix;
            suffix++;
        }
        return candidate;
    }

    private String generatePassword() {
        // Generate a random 12‑character password using SecureRandom and Base64
        SecureRandom random = new SecureRandom();
        byte[] bytes = new byte[9]; // 9 bytes -> 12 Base64 chars (without padding)
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /* =========================
       READ (single admin)
       ========================= */
    @Transactional(readOnly = true)
    public AdminRow getDetailedUser(String username) {
        AppUser user = repo.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));
        // Dual-role accounts (pharmacist/staff promoted to admin) keep their
        // user profile — fall back to it instead of failing with
        // "Profile not found" (same convention as listAdminsDetailed).
        AdminProfile profile = adminProfileRepo.findByUserId(user.getId()).orElse(null);
        if (profile != null) {
            return new AdminRow(
                    user.getId(),
                    user.getUsername(),
                    user.isEnabled(),
                    profile.getFullName(),
                    profile.getEmail(),
                    profile.getPhone(),
                    null);
        }
        com.nordicframtiden.security.model.UserProfile userProfile =
            userProfileRepo.findByUserId(user.getId()).orElse(null);
        return new AdminRow(
                user.getId(),
                user.getUsername(),
                user.isEnabled(),
                userProfile != null ? userProfile.getFullName() : null,
                userProfile != null ? userProfile.getEmail() : null,
                userProfile != null ? userProfile.getPhone() : null,
                null);
    }

    @Transactional
    /** Re-sends the welcome invite so the admin can set their own password. */
    public boolean resendAdminInvite(Long id) {
        return passwordResetService.sendWelcomeInvite(id);
    }

    /* =========================
       DELETE
       ========================= */
    @Transactional
    public void deleteAdmin(Long id) {
        AppUser user = repo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        if (user.getRoles() == null || !user.getRoles().contains(Role.ADMIN)) {
            throw new IllegalArgumentException("User is not an admin");
        }

        // Safety rails: never let the workspace lock itself out.
        String currentUsername = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication() != null
                ? org.springframework.security.core.context.SecurityContextHolder.getContext()
                      .getAuthentication().getName()
                : null;
        if (currentUsername != null && currentUsername.equals(user.getUsername())) {
            throw new IllegalArgumentException("You cannot delete your own admin account");
        }
        repo.lockAllAdmins(); // serialize concurrent deletions before the last-admin count
        long admins = repo.countByRole(Role.ADMIN);
        if (admins <= 1) {
            throw new IllegalArgumentException("Cannot delete the last remaining admin account");
        }
        // Dual-role ADMIN+USER/STAFF accounts work shifts: same unpaid-payroll
        // guard as UserService.deleteUser (this path is also reached directly
        // from the admin management API).
        if (deletionPolicy != null && deletionPolicy.hasUnpaidShifts(id)) {
            throw new com.nordicframtiden.gdpr.UserDeletionBlockedException(
                "Radering blockerad: användaren har arbetspass som ännu inte har betalats ut. "
                    + "Använd raderingsbegäranden i stället — raderingen schemaläggs automatiskt "
                    + "efter sista lönemånaden.");
        }

        // Delete rooms created by this admin; messages inside cascade with the room.
        chatRoomRepo.deleteByCreatedBy(user);
        // Messages sent by this admin in other rooms (e.g. channels).
        chatMessageRepo.deleteBySender(user);
        // Attachments they uploaded (uploader_id has no FK; deliveries cascade with the attachment).
        chatAttachmentRepo.deleteByUploaderId(id);
        // Frozen payslip snapshots (no FK; avoid orphan rows).
        payslipSnapshotRepo.deleteByUserId(id);
        // Automatic payslip delivery queue/audit rows (FK cascades, but keep
        // the explicit sweep in line with the other payroll data).
        payslipDeliveryRequestRepo.deleteByUserId(id);
        // Reactions by this user and call history they started.
        chatReactionRepo.deleteByUser(user);
        callHistoryRepo.deleteByCaller(user);
        chatPushSubscriptionRepo.deleteByUser(user);
        staffShiftRepo.deleteByUser(user);
        availabilityRequestRepo.deleteByUser(user);
        // Channel memberships (the join table cascades server-side, but the
        // rows must go before the user row in the same transaction).
        chatRoomMemberRepo.deleteByUserId(id);
        // Outstanding password-reset links die with the account.
        resetTokenRepo.deleteByUserId(id);
        // The admin's own profile documents (user_id cascades server-side;
        // delete explicitly so the payload is purged, not orphaned).
        profileDocumentRepo.deleteByUserId(id);

        // Dual-role accounts (ADMIN + USER) keep a pharmacist user_profile for
        // email/phone; remove it too, or the FK below blocks the delete.
        userProfileRepo.findByUserId(id).ifPresent(userProfileRepo::delete);

        adminProfileRepo.findByUserId(id).ifPresent(adminProfileRepo::delete);
        repo.delete(user);
    }
}
