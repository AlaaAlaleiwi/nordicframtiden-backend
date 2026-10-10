package com.nordicframtiden.security.service;

import com.nordicframtiden.availability.AvailabilityRequestRepository;
import com.nordicframtiden.chat.CallHistoryRepository;
import com.nordicframtiden.chat.ChatMessageRepository;
import com.nordicframtiden.chat.ChatRoomRepository;
import com.nordicframtiden.company.StaffShiftRepository;
import com.nordicframtiden.security.model.AppUser;
import com.nordicframtiden.security.model.Permission;
import com.nordicframtiden.security.model.Role;
import com.nordicframtiden.security.model.UserProfile;
import com.nordicframtiden.security.repo.AppUserRepository;
import com.nordicframtiden.security.repo.UserProfileRepository;
import com.nordicframtiden.settings.EmailService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Year;
import java.util.List;
import java.util.Set;

@Service
public class UserService {

  private final AppUserRepository userRepo;
  private final UserProfileRepository profileRepo;
  private final PasswordEncoder encoder;
  private final EmailService emailService;
  // Tables that reference app_user without ON DELETE CASCADE; their rows must
  // be removed explicitly before deleting the user or Postgres rejects the
  // delete with a foreign-key violation.
  private final StaffShiftRepository staffShifts;
  private final AvailabilityRequestRepository availabilityRequests;
  private final CallHistoryRepository callHistory;
  private final ChatRoomRepository chatRooms;
  private final ChatMessageRepository chatMessages;
  // FK-less tables (no FK to app_user, so deletes are never cascaded) that
  // would otherwise leave orphan rows behind.
  private final com.nordicframtiden.chat.ChatAttachmentRepository chatAttachments;
  private final com.nordicframtiden.service.model.PayslipSnapshotRepository payslipSnapshots;
  // GDPR Art. 17 completeness: tables referencing the user that the original
  // deletion path missed (memberships, reactions, push tokens, reset tokens,
  // profile documents). Their leftover rows either block the delete (FK) or
  // keep personal data alive after erasure.
  private final com.nordicframtiden.chat.ChatRoomMemberRepository chatRoomMembers;
  private final com.nordicframtiden.chat.ChatReactionRepository chatReactions;
  private final com.nordicframtiden.chat.ChatPushSubscriptionRepository pushSubscriptions;
  private final com.nordicframtiden.security.repo.PasswordResetTokenRepository resetTokens;
  private final com.nordicframtiden.documents.ProfileDocumentRepository profileDocuments;
  /** Nullable: unit tests exercise the pharmacist path without it. */
  private final com.nordicframtiden.admin.AdminService adminService;
  /** Nullable: unit tests construct UserService without the policy. */
  private final com.nordicframtiden.gdpr.DeletionPolicy deletionPolicy;

  public UserService(AppUserRepository userRepo, UserProfileRepository profileRepo, PasswordEncoder encoder, EmailService emailService,
                     StaffShiftRepository staffShifts, AvailabilityRequestRepository availabilityRequests,
                     CallHistoryRepository callHistory, ChatRoomRepository chatRooms, ChatMessageRepository chatMessages,
                     com.nordicframtiden.chat.ChatAttachmentRepository chatAttachments,
                     com.nordicframtiden.service.model.PayslipSnapshotRepository payslipSnapshots,
                     com.nordicframtiden.chat.ChatRoomMemberRepository chatRoomMembers,
                     com.nordicframtiden.chat.ChatReactionRepository chatReactions,
                     com.nordicframtiden.chat.ChatPushSubscriptionRepository pushSubscriptions,
                     com.nordicframtiden.security.repo.PasswordResetTokenRepository resetTokens,
                     com.nordicframtiden.documents.ProfileDocumentRepository profileDocuments) {
    this(userRepo, profileRepo, encoder, emailService, staffShifts, availabilityRequests,
        callHistory, chatRooms, chatMessages, chatAttachments, payslipSnapshots,
        chatRoomMembers, chatReactions, pushSubscriptions, resetTokens, profileDocuments, null, null);
  }

  /** Production constructor: ADMIN targets delegate to the admin cleanup path. */
  @Autowired
  public UserService(AppUserRepository userRepo, UserProfileRepository profileRepo, PasswordEncoder encoder, EmailService emailService,
                     StaffShiftRepository staffShifts, AvailabilityRequestRepository availabilityRequests,
                     CallHistoryRepository callHistory, ChatRoomRepository chatRooms, ChatMessageRepository chatMessages,
                     com.nordicframtiden.chat.ChatAttachmentRepository chatAttachments,
                     com.nordicframtiden.service.model.PayslipSnapshotRepository payslipSnapshots,
                     com.nordicframtiden.chat.ChatRoomMemberRepository chatRoomMembers,
                     com.nordicframtiden.chat.ChatReactionRepository chatReactions,
                     com.nordicframtiden.chat.ChatPushSubscriptionRepository pushSubscriptions,
                     com.nordicframtiden.security.repo.PasswordResetTokenRepository resetTokens,
                     com.nordicframtiden.documents.ProfileDocumentRepository profileDocuments,
                     com.nordicframtiden.gdpr.DeletionPolicy deletionPolicy,
                     @org.springframework.context.annotation.Lazy com.nordicframtiden.admin.AdminService adminService) {
    this.userRepo = userRepo;
    this.profileRepo = profileRepo;
    this.encoder = encoder;
    this.emailService = emailService;
    this.staffShifts = staffShifts;
    this.availabilityRequests = availabilityRequests;
    this.callHistory = callHistory;
    this.chatRooms = chatRooms;
    this.chatMessages = chatMessages;
    this.chatAttachments = chatAttachments;
    this.payslipSnapshots = payslipSnapshots;
    this.chatRoomMembers = chatRoomMembers;
    this.chatReactions = chatReactions;
    this.pushSubscriptions = pushSubscriptions;
    this.resetTokens = resetTokens;
    this.profileDocuments = profileDocuments;
    this.adminService = adminService;
    this.deletionPolicy = deletionPolicy;
  }

  // ---------- Records ----------

  public record UserRow(
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
      Long photoId,
      Set<Permission> permissions,
      String password,
      boolean admin,
      com.nordicframtiden.gdpr.DeletionPolicy.Info deletionPolicy
  ) {}

  public record DetailedUser(
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
      Long photoId,
      Set<Permission> permissions,
      boolean admin,
      com.nordicframtiden.gdpr.DeletionPolicy.Info deletionPolicy
  ) {}

  /** Photo id for a user, or null when they have no photo. */
  public Long photoIdOf(Long userId) {
    return userRepo.findById(userId).map(u -> u.getPhotoId()).orElse(null);
  }

  private static Long photoId(AppUser u) {
    return u == null ? null : u.getPhotoId();
  }

  // ---------- Validation helpers ----------

  /** HOURLY (default) or MONTHLY; null/blank/unknown falls back to HOURLY. */
  private static String normalizePayType(String payType) {
    return payType != null && payType.trim().equalsIgnoreCase("MONTHLY")
        ? "MONTHLY"
        : "HOURLY";
  }

  private static void validateYear(Integer yearOfBirth) {
    if (yearOfBirth == null) throw new IllegalArgumentException("yearOfBirth is required");
    int now = Year.now().getValue();
    if (yearOfBirth < 1900 || yearOfBirth > now) throw new IllegalArgumentException("Invalid yearOfBirth");
  }

  private static void validateCodes(String countyCode, String municipalityCode) {
    if (countyCode == null || countyCode.isBlank()) throw new IllegalArgumentException("countyCode is required");
    if (municipalityCode == null || municipalityCode.isBlank()) throw new IllegalArgumentException("municipalityCode is required");

    String cc = countyCode.trim();
    String mc = municipalityCode.trim();

    if (!cc.matches("\\d{2}")) throw new IllegalArgumentException("Invalid countyCode");
    if (!mc.matches("\\d{4}")) throw new IllegalArgumentException("Invalid municipalityCode");

    if (!mc.startsWith(cc)) throw new IllegalArgumentException("municipalityCode must start with countyCode");
  }

  private static Set<Permission> safePerms(Set<Permission> perms) {
    return perms == null ? Set.of() : perms;
  }

  private static boolean callerIs(AppUser user) {
    var auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
    return auth != null && user.getUsername() != null && user.getUsername().equals(auth.getName());
  }

  private com.nordicframtiden.admin.model.AdminProfileRepository adminProfiles;

  /** Optional so the many unit-test constructions stay unchanged. */
  @Autowired(required = false)
  public void setAdminProfiles(com.nordicframtiden.admin.model.AdminProfileRepository adminProfiles) {
    this.adminProfiles = adminProfiles;
  }

  /**
   * Password reset looks the email up case-insensitively across user AND
   * admin profiles and needs exactly one match — so duplicates are refused
   * the same way, or one account could hijack or block another's reset.
   */
  private boolean emailTaken(String email) {
    String trimmed = email.trim();
    return profileRepo.findByEmailIgnoreCase(trimmed).isPresent()
        || (adminProfiles != null && adminProfiles.findByEmailIgnoreCase(trimmed).isPresent());
  }

  private static boolean callerIsAdmin() {
    var auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
    return auth != null && auth.getAuthorities().stream()
        .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
  }

  // ---------- Read ----------

  public UserProfile getProfileByUserId(Long userId) {
    return profileRepo.findByUserId(userId)
        .orElseThrow(() -> new IllegalArgumentException("Profile not found"));
  }

  public DetailedUser getDetailedByUsername(String username) {
    AppUser u = userRepo.findByUsername(username)
        .orElseThrow(() -> new IllegalArgumentException("User not found"));

    UserProfile profile = profileRepo.findByUserId(u.getId()).orElse(null);

    return new DetailedUser(
        u.getId(),
        u.getUsername(),
        u.isEnabled(),
        profile != null ? profile.getFullName() : u.getUsername(),
        profile != null ? profile.getEmail() : null,
        profile != null ? profile.getPhone() : null,
        profile != null ? profile.getHourlyCost() : null,
        profile != null ? profile.getPayType() : null,
        profile != null ? profile.getMonthlySalary() : null,
        profile != null ? profile.getYearOfBirth() : null,
        profile != null ? profile.getCountyCode() : null,
        profile != null ? profile.getMunicipalityCode() : null,
        photoId(u),
        safePerms(u.getPermissions()),
        u.getRoles() != null && u.getRoles().contains(Role.ADMIN),
        deletionPolicy == null ? null : deletionPolicy.infoFor(u.getId())
    );
  }

  public DetailedUser getDetailedById(Long id) {
    return userRepo.findById(id)
        .map(u -> {
          UserProfile profile = profileRepo.findByUserId(u.getId()).orElse(null);
          return new DetailedUser(
              u.getId(),
              u.getUsername(),
              u.isEnabled(),
              profile != null ? profile.getFullName() : u.getUsername(),
              profile != null ? profile.getEmail() : null,
              profile != null ? profile.getPhone() : null,
              profile != null ? profile.getHourlyCost() : null,
              profile != null ? profile.getPayType() : null,
              profile != null ? profile.getMonthlySalary() : null,
              profile != null ? profile.getYearOfBirth() : null,
              profile != null ? profile.getCountyCode() : null,
              profile != null ? profile.getMunicipalityCode() : null,
              photoId(u),
              safePerms(u.getPermissions()),
              u.getRoles() != null && u.getRoles().contains(Role.ADMIN),
              deletionPolicy == null ? null : deletionPolicy.infoFor(u.getId())
      );
        })
        .orElseThrow(() -> new IllegalArgumentException("User not found"));
  }

  public List<UserRow> listDetailedByRole(Role role) {
    return userRepo.findAllByRole(role).stream().map(u -> {
      UserProfile p = profileRepo.findByUserId(u.getId()).orElse(null);
      return new UserRow(
          u.getId(),
          u.getUsername(),
          u.isEnabled(),
          p != null ? p.getFullName() : null,
          p != null ? p.getEmail() : null,
          p != null ? p.getPhone() : null,
          p != null ? p.getHourlyCost() : null,
          p != null ? p.getPayType() : null,
          p != null ? p.getMonthlySalary() : null,
          p != null ? p.getYearOfBirth() : null,
          p != null ? p.getCountyCode() : null,
          p != null ? p.getMunicipalityCode() : null,
          photoId(u),
          safePerms(u.getPermissions()),
          null,
          u.getRoles() != null && u.getRoles().contains(Role.ADMIN),
        deletionPolicy == null ? null : deletionPolicy.infoFor(u.getId())
      );
    }).toList();
  }

  // ---------- Create ----------

  @Transactional
  @PreAuthorize("hasRole('ADMIN') or (hasAuthority('PERM_PEOPLE') and #role == T(com.nordicframtiden.security.model.Role).USER)")
  public UserRow createWithProfile(
      Role role,
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
      Set<Permission> permissions // ✅ NEW PARAM
  ) {

    if (role != Role.USER && role != Role.STAFF) {
      throw new IllegalArgumentException("Only USER or STAFF can be created here");
    }

    if (fullName == null || fullName.isBlank()) throw new IllegalArgumentException("fullName is required");
    if (email == null || email.isBlank()) throw new IllegalArgumentException("email is required");
    if (phone == null || phone.isBlank()) throw new IllegalArgumentException("phone is required");

    validateYear(yearOfBirth);
    validateCodes(countyCode, municipalityCode);

    if (emailTaken(email)) throw new IllegalArgumentException("Email already exists");
    if (profileRepo.existsByPhone(phone.trim())) throw new IllegalArgumentException("Phone already exists");

    String username = generateUniqueUsername(fullName);
    String rawPassword = generatePassword(12);

    AppUser u = new AppUser();
    u.setUsername(username);
    u.setPasswordHash(encoder.encode(rawPassword));
    u.setEnabled(enabled);
    u.setRoles(Set.of(role));

    // ✅ Permissions only matter for STAFF
    if (role == Role.STAFF) u.setPermissions(safePerms(permissions));
    else u.setPermissions(Set.of());

    u = userRepo.save(u);

    UserProfile p = new UserProfile();
    p.setFullName(fullName.trim());
    p.setEmail(email.trim());
    p.setPhone(phone.trim());
    p.setHourlyCost(hourlyCost);
    p.setPayType(normalizePayType(payType));
    p.setMonthlySalary(
        "MONTHLY".equals(p.getPayType()) ? monthlySalary : null);

    p.setYearOfBirth(yearOfBirth);
    p.setCountyCode(countyCode.trim());
    p.setMunicipalityCode(municipalityCode.trim());

    p.setUser(u);
    profileRepo.save(p);

    return new UserRow(
        u.getId(),
        u.getUsername(),
        u.isEnabled(),
        p.getFullName(),
        p.getEmail(),
        p.getPhone(),
        p.getHourlyCost(),
        p.getPayType(),
        p.getMonthlySalary(),
        p.getYearOfBirth(),
        p.getCountyCode(),
        p.getMunicipalityCode(),
        photoId(u),
        safePerms(u.getPermissions()),
        rawPassword,
        u.getRoles() != null && u.getRoles().contains(Role.ADMIN),
        deletionPolicy == null ? null : deletionPolicy.infoFor(u.getId())
    );
  }

  // ---------- Update ----------

  @Transactional
  @PreAuthorize("@accountAuthorization.canManage(authentication, #id)")
  public UserRow updateWithProfile(
      Long id,
      String fullName,
      String email,
      String phone,
      Boolean enabled,
      BigDecimal hourlyCost,
      String payType,
      BigDecimal monthlySalary,
      Integer yearOfBirth,
      String countyCode,
      String municipalityCode,
      Set<Permission> permissions // ✅ NEW PARAM
  ) {

    AppUser u = userRepo.findById(id).orElseThrow(() -> new IllegalArgumentException("User not found"));

    if (enabled != null) u.setEnabled(enabled);

    UserProfile p = profileRepo.findByUserId(id)
        .orElseThrow(() -> new IllegalArgumentException("Profile not found"));

    if (fullName != null && !fullName.isBlank()) p.setFullName(fullName.trim());

    if (email != null && !email.isBlank() && !email.equalsIgnoreCase(p.getEmail())) {
      // Self-service password reset trusts the stored email, so whoever can
      // change it can take over the account. PERM_PEOPLE staff may edit
      // pharmacists but not redirect their email — that stays admin-only
      // (the owner may change their own, via PUT /api/users/me).
      if (!callerIsAdmin() && !callerIs(u)) {
        throw new org.springframework.security.access.AccessDeniedException(
            "Endast administratörer kan ändra en annan användares e-postadress.");
      }
      if (emailTaken(email)) throw new IllegalArgumentException("Email already exists");
      p.setEmail(email.trim());
    }

    if (phone != null && !phone.isBlank() && !phone.equals(p.getPhone())) {
      // Check the value that is saved (trimmed), or a padded duplicate slips
      // past the check and hits the unique constraint as a 500.
      if (profileRepo.existsByPhone(phone.trim())) throw new IllegalArgumentException("Phone already exists");
      p.setPhone(phone.trim());
    }

    if (hourlyCost != null) p.setHourlyCost(hourlyCost);

    // Pay type: switchable on update. MONTHLY requires a salary amount;
    // switching back to HOURLY clears the stored monthly salary.
    if (payType != null) {
      p.setPayType(normalizePayType(payType));
      if ("MONTHLY".equals(p.getPayType())) {
        BigDecimal nextSalary = monthlySalary != null ? monthlySalary : p.getMonthlySalary();
        if (nextSalary == null || nextSalary.signum() < 0) {
          throw new IllegalArgumentException("monthlySalary is required for MONTHLY pay type");
        }
        p.setMonthlySalary(nextSalary);
      } else {
        p.setMonthlySalary(null);
      }
    } else if (monthlySalary != null && "MONTHLY".equals(p.getPayType())) {
      p.setMonthlySalary(monthlySalary);
    }

    if (yearOfBirth != null) {
      validateYear(yearOfBirth);
      p.setYearOfBirth(yearOfBirth);
    }

    boolean anyCodeProvided =
        (countyCode != null && !countyCode.isBlank()) ||
        (municipalityCode != null && !municipalityCode.isBlank());

    if (anyCodeProvided) {
      String nextCounty = (countyCode == null || countyCode.isBlank()) ? p.getCountyCode() : countyCode.trim();
      String nextMunicipality = (municipalityCode == null || municipalityCode.isBlank()) ? p.getMunicipalityCode() : municipalityCode.trim();
      validateCodes(nextCounty, nextMunicipality);
      p.setCountyCode(nextCounty);
      p.setMunicipalityCode(nextMunicipality);
    }

    // ✅ Update permissions only if this user is STAFF and request includes permissions
    boolean isStaff = u.getRoles() != null && u.getRoles().contains(Role.STAFF);
    if (isStaff && permissions != null) {
      u.setPermissions(safePerms(permissions));
    }

    profileRepo.save(p);
    userRepo.save(u);

    return new UserRow(
        u.getId(),
        u.getUsername(),
        u.isEnabled(),
        p.getFullName(),
        p.getEmail(),
        p.getPhone(),
        p.getHourlyCost(),
        p.getPayType(),
        p.getMonthlySalary(),
        p.getYearOfBirth(),
        p.getCountyCode(),
        p.getMunicipalityCode(),
        photoId(u),
        safePerms(u.getPermissions()),
        null,
        u.getRoles() != null && u.getRoles().contains(Role.ADMIN),
        deletionPolicy == null ? null : deletionPolicy.infoFor(u.getId())
    );
  }

  @Transactional
  @PreAuthorize("isAuthenticated() and #username == authentication.name")
  public UserRow updateOwnProfile(
      String username,
      String fullName,
      String email,
      String phone,
      Integer yearOfBirth,
      String countyCode,
      String municipalityCode
  ) {
    AppUser user = userRepo.findByUsername(username)
        .orElseThrow(() -> new IllegalArgumentException("User not found"));

    return updateWithProfile(
        user.getId(),
        fullName,
        email,
        phone,
        null,
        null,
        null,
        null,
        yearOfBirth,
        countyCode,
        municipalityCode,
        null
    );
  }

  // ---------- Delete ----------

  @Transactional
  @PreAuthorize("hasRole('ADMIN')")
  public void deleteUser(Long id) {
    AppUser u = userRepo.findById(id).orElseThrow(() -> new IllegalArgumentException("User not found"));

    // Direct deletion guard: while the user has shifts in the current month
    // (or last month, until its payslip is delivered) they are still owed pay —
    // deleting now would wipe the payroll records. Send the admin to the
    // deletion-request flow instead; it schedules the deletion after the last
    // payroll month. The nightly GDPR job hits this guard too and retries.
    // Runs before the ADMIN delegation: dual-role ADMIN+USER accounts work
    // shifts too.
    if (deletionPolicy != null && deletionPolicy.hasUnpaidShifts(id)) {
      throw new com.nordicframtiden.gdpr.UserDeletionBlockedException(
          "Radering blockerad: användaren har arbetspass den här månaden och får sin lön nästa månad. "
              + "Använd raderingsbegäranden i stället — raderingen schemaläggs automatiskt "
              + "efter sista lönemånaden.");
    }

    // ADMIN accounts (including dual-role ADMIN+USER shown in People) need
    // the admin cleanup path — user_profile, admin_profile, self-delete and
    // last-admin guards. Delegating keeps one complete deletion routine.
    if (u.getRoles() != null && u.getRoles().contains(Role.ADMIN)) {
      if (adminService == null) {
        // No injected collaborator (unit tests of the pharmacist path): the
        // old explicit refusal keeps those tests meaningful.
        throw new IllegalArgumentException("Cannot delete ADMIN from /api/users");
      }
      adminService.deleteAdmin(id);
      return;
    }


    // Delete dependent rows whose foreign keys lack ON DELETE CASCADE,
    // otherwise the final delete fails (staff_shift, availability_request,
    // call_history, chat_message, chat_room.created_by).
    chatRooms.deleteByCreatedBy(u);
    chatMessages.deleteBySender(u);
    // GDPR Art. 17: every other table referencing the account — reactions
    // and memberships (FKs that would block the delete), push tokens, reset
    // tokens and profile documents (orphaned personal data otherwise).
    chatReactions.deleteByUser(u);
    chatRoomMembers.deleteByUserId(id);
    pushSubscriptions.deleteByUser(u);
    resetTokens.deleteByUserId(id);
    profileDocuments.deleteByUserId(id);
    staffShifts.deleteByUser(u);
    availabilityRequests.deleteByUser(u);
    callHistory.deleteByCaller(u);
    // FK-less tables: no FK to app_user, so nothing cascades — clean up or
    // orphan rows remain (attachments the user uploaded, frozen payslips).
    chatAttachments.deleteByUploaderId(id);
    payslipSnapshots.deleteByUserId(id);

    profileRepo.findByUserId(id).ifPresent(profileRepo::delete);
    userRepo.delete(u);
  }

  // ---------- Reset Password ----------

  @Transactional
  @PreAuthorize("hasRole('ADMIN')")
  public UserRow resetPassword(Long id) {
    AppUser u = userRepo.findById(id)
        .orElseThrow(() -> new IllegalArgumentException("User not found"));

    if (u.getRoles() != null && u.getRoles().contains(Role.ADMIN)) {
      throw new IllegalArgumentException("Cannot reset ADMIN password from /api/users");
    }

    String rawPassword = generatePassword(12);
    u.setPasswordHash(encoder.encode(rawPassword));
    userRepo.save(u);

    UserProfile p = profileRepo.findByUserId(u.getId()).orElse(null);
    if (p != null && p.getEmail() != null && !p.getEmail().isBlank()) {
      emailService.sendPasswordResetEmail(p.getEmail(), u.getUsername(), rawPassword);
    }

    return new UserRow(
        u.getId(),
        u.getUsername(),
        u.isEnabled(),
        p != null ? p.getFullName() : null,
        p != null ? p.getEmail() : null,
        p != null ? p.getPhone() : null,
        p != null ? p.getHourlyCost() : null,
        p != null ? p.getPayType() : null,
        p != null ? p.getMonthlySalary() : null,
        p != null ? p.getYearOfBirth() : null,
        p != null ? p.getCountyCode() : null,
        p != null ? p.getMunicipalityCode() : null,
        photoId(u),
        safePerms(u.getPermissions()),
        rawPassword,
        u.getRoles() != null && u.getRoles().contains(Role.ADMIN),
        deletionPolicy == null ? null : deletionPolicy.infoFor(u.getId())
    );
  }

  // ---------- Username + Password helpers ----------

  private String generateUniqueUsername(String fullName) {
    String base = (fullName == null ? "user" : fullName)
        .trim()
        .toLowerCase()
        .replaceAll("[^a-z0-9]+", ".")
        .replaceAll("^\\.|\\.$", "");

    if (base.isBlank()) base = "user";

    String candidate = base;
    int i = 1;
    while (userRepo.existsByUsername(candidate)) {
      i++;
      candidate = base + i;
    }
    return candidate;
  }

  private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789!@#$%";
  private static final SecureRandom RNG = new SecureRandom();

  private String generatePassword(int len) {
    StringBuilder sb = new StringBuilder(len);
    for (int i = 0; i < len; i++) {
      sb.append(ALPHABET.charAt(RNG.nextInt(ALPHABET.length())));
    }
    return sb.toString();
  }
}
