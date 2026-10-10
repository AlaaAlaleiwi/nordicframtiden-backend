package com.nordicframtiden.admin;

import com.nordicframtiden.admin.model.AdminProfileRepository;
import com.nordicframtiden.availability.AvailabilityRequestRepository;
import com.nordicframtiden.chat.CallHistoryRepository;
import com.nordicframtiden.chat.ChatMessageRepository;
import com.nordicframtiden.chat.ChatPushSubscriptionRepository;
import com.nordicframtiden.chat.ChatReactionRepository;
import com.nordicframtiden.chat.ChatRoomMemberRepository;
import com.nordicframtiden.chat.ChatRoomRepository;
import com.nordicframtiden.chat.ChatAttachmentRepository;
import com.nordicframtiden.company.StaffShiftRepository;
import com.nordicframtiden.documents.ProfileDocumentRepository;
import com.nordicframtiden.security.model.AppUser;
import com.nordicframtiden.security.model.Role;
import com.nordicframtiden.security.repo.AppUserRepository;
import com.nordicframtiden.security.service.PasswordResetService;
import com.nordicframtiden.settings.EmailService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Regression test for the production 500 on DELETE /api/admins/{id} (Cloud Run
 * revision 00069, 2026-09-27): an old admin with the full real-world footprint
 * (chat rooms, memberships, messages, reactions, attachments, call history,
 * reset tokens, documents) could not be deleted because the cleanup missed
 * referencing rows. Every FK that points at app_user without a cascade must
 * be handled BEFORE repo.delete(user).
 */
@ExtendWith(MockitoExtension.class)
class AdminDeletionFootprintTest {

    @Mock private AppUserRepository repo;
    @Mock private AdminProfileRepository adminProfileRepo;
    @Mock private com.nordicframtiden.security.repo.UserProfileRepository userProfileRepo;
    @Mock private PasswordEncoder encoder;
    @Mock private EmailService emailService;
    @Mock private PasswordResetService passwordResetService;
    @Mock private ChatMessageRepository chatMessageRepo;
    @Mock private ChatReactionRepository chatReactionRepo;
    @Mock private ChatRoomRepository chatRoomRepo;
    @Mock private ChatRoomMemberRepository chatRoomMemberRepo;
    @Mock private CallHistoryRepository callHistoryRepo;
    @Mock private ChatPushSubscriptionRepository chatPushSubscriptionRepo;
    @Mock private StaffShiftRepository staffShiftRepo;
    @Mock private AvailabilityRequestRepository availabilityRequestRepo;
    @Mock private com.nordicframtiden.chat.ChatAttachmentRepository chatAttachmentRepo;
    @Mock private com.nordicframtiden.service.model.PayslipSnapshotRepository payslipSnapshotRepo;
    @Mock private com.nordicframtiden.service.model.PayslipDeliveryRequestRepository payslipDeliveryRequestRepo;
    @Mock private com.nordicframtiden.documents.ProfileDocumentRepository profileDocumentRepo;
    @Mock private com.nordicframtiden.security.repo.PasswordResetTokenRepository resetTokenRepo;

    private AdminService service;
    private AppUser admin;

    @BeforeEach
    void setUp() {
        service = new AdminService(repo, adminProfileRepo, userProfileRepo, encoder,
            emailService, passwordResetService, chatMessageRepo, chatReactionRepo,
            chatRoomRepo, callHistoryRepo, chatPushSubscriptionRepo, staffShiftRepo,
            availabilityRequestRepo,
            profileDocumentRepo,
            chatAttachmentRepo,
            payslipSnapshotRepo,
            payslipDeliveryRequestRepo,
            chatRoomMemberRepo,
            resetTokenRepo);

        admin = new AppUser();
        admin.setId(1L);
        admin.setUsername("admin");
        admin.setRoles(new java.util.HashSet<>(java.util.Set.of(Role.ADMIN)));
        lenient().when(repo.findById(1L)).thenReturn(Optional.of(admin));
        // Two admins exist and the caller is someone else: deletions allowed.
        lenient().when(repo.countByRole(Role.ADMIN)).thenReturn(2L);
        lenient().when(userProfileRepo.findByUserId(1L)).thenReturn(Optional.empty());
        // Authenticated caller is a different admin (not self-delete).
        org.springframework.security.core.context.SecurityContextHolder.getContext()
            .setAuthentication(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                "other-admin", "n/a", java.util.List.of()));
    }

    @AfterEach
    void clearSecurityContext() {
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }

    @Test
    void deleting_an_old_admin_cleans_every_referencing_table() {
        assertDoesNotThrow(() -> service.deleteAdmin(1L));

        org.mockito.InOrder inOrder = org.mockito.Mockito.inOrder(
            chatRoomRepo, chatMessageRepo, chatAttachmentRepo, payslipSnapshotRepo,
            chatReactionRepo, callHistoryRepo, chatPushSubscriptionRepo,
            staffShiftRepo, availabilityRequestRepo, chatRoomMemberRepo,
            resetTokenRepo, profileDocumentRepo, repo);

        // Rooms first (their messages cascade server-side), then the admin's
        // messages in OTHER rooms, then the FK-less tables.
        inOrder.verify(chatRoomRepo).deleteByCreatedBy(admin);
        inOrder.verify(chatMessageRepo).deleteBySender(admin);
        inOrder.verify(chatAttachmentRepo).deleteByUploaderId(1L);
        inOrder.verify(payslipSnapshotRepo).deleteByUserId(1L);
        inOrder.verify(chatReactionRepo).deleteByUser(admin);
        inOrder.verify(callHistoryRepo).deleteByCaller(admin);
        inOrder.verify(chatPushSubscriptionRepo).deleteByUser(admin);
        inOrder.verify(staffShiftRepo).deleteByUser(admin);
        inOrder.verify(availabilityRequestRepo).deleteByUser(admin);
        // New: memberships, reset tokens, and own profile documents.
        inOrder.verify(chatRoomMemberRepo).deleteByUserId(1L);
        inOrder.verify(resetTokenRepo).deleteByUserId(1L);
        inOrder.verify(profileDocumentRepo).deleteByUserId(1L);
        inOrder.verify(repo).delete(admin);
    }

    @Test
    void deleting_an_unknown_admin_is_a_clean_404_style_failure() {
        when(repo.findById(999L)).thenReturn(Optional.empty());
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
            () -> service.deleteAdmin(999L));
        org.mockito.Mockito.verify(repo, org.mockito.Mockito.never()).delete(any());
    }

    @Test
    void deleting_a_non_admin_is_rejected() {
        AppUser pharmacist = new AppUser();
        pharmacist.setId(5L);
        pharmacist.setUsername("pharm");
        pharmacist.setRoles(new java.util.HashSet<>(java.util.Set.of(Role.USER)));
        when(repo.findById(5L)).thenReturn(Optional.of(pharmacist));

        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
            () -> service.deleteAdmin(5L));
        org.mockito.Mockito.verify(repo, org.mockito.Mockito.never()).delete(any());
    }

    @Test
    void deleting_your_own_admin_account_is_rejected() {
        org.springframework.security.core.context.SecurityContextHolder.getContext()
            .setAuthentication(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                "admin", "n/a", java.util.List.of()));

        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
            () -> service.deleteAdmin(1L));
        org.mockito.Mockito.verify(repo, org.mockito.Mockito.never()).delete(any());
    }

    @Test
    void deleting_the_last_admin_is_rejected() {
        when(repo.countByRole(Role.ADMIN)).thenReturn(1L);

        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
            () -> service.deleteAdmin(1L));
        org.mockito.Mockito.verify(repo, org.mockito.Mockito.never()).delete(any());
    }

    @Test
    void deleting_a_dual_role_admin_with_unpaid_shifts_is_blocked() {
        var policy = org.mockito.Mockito.mock(com.nordicframtiden.gdpr.DeletionPolicy.class);
        when(policy.hasUnpaidShifts(1L)).thenReturn(true);
        service.setDeletionPolicy(policy);

        org.junit.jupiter.api.Assertions.assertThrows(
            com.nordicframtiden.gdpr.UserDeletionBlockedException.class, () -> service.deleteAdmin(1L));
        org.mockito.Mockito.verify(repo, org.mockito.Mockito.never()).delete(any());
        org.mockito.Mockito.verify(staffShiftRepo, org.mockito.Mockito.never()).deleteByUser(any());
    }
}
