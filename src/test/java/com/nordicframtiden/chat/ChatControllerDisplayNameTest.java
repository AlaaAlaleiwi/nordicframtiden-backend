package com.nordicframtiden.chat;

import com.nordicframtiden.admin.model.AdminProfile;
import com.nordicframtiden.admin.model.AdminProfileRepository;
import com.nordicframtiden.security.model.AppUser;
import com.nordicframtiden.security.model.Role;
import com.nordicframtiden.security.model.UserProfile;
import com.nordicframtiden.security.repo.AppUserRepository;
import com.nordicframtiden.security.repo.UserProfileRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Chat must display full names, never raw usernames: pure-admin accounts keep
 * their name in AdminProfile (no UserProfile), so the participant mapper has
 * to consult both profile tables before degrading to the username.
 */
@ExtendWith(MockitoExtension.class)
class ChatControllerDisplayNameTest {

    @Mock ChatService service;
    @Mock ChatRoomMemberRepository members;
    @Mock ChatMessageRepository messages;
    @Mock ChatReactionRepository reactions;
    @Mock ChatAttachmentRepository attachments;
    @Mock ChatAttachmentDeliveryRepository deliveries;
    @Mock ChatAttachmentPurgeService purgeService;
    @Mock AppUserRepository users;
    @Mock UserProfileRepository profiles;
    @Mock AdminProfileRepository adminProfiles;
    @Mock ChatPresence presence;
    @Mock com.nordicframtiden.security.service.UserService userService;

    @InjectMocks ChatController controller;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(controller)
            .setMessageConverters(new org.springframework.http.converter.json.MappingJackson2HttpMessageConverter())
            .build();
    }

    /** A pure admin: name lives in AdminProfile, no UserProfile. */
    private AppUser adminUser() {
        AppUser user = new AppUser();
        user.setId(2L);
        user.setUsername("alaa.admin");
        user.setEnabled(true);
        user.setRoles(new java.util.HashSet<>(Set.of(Role.ADMIN)));
        return user;
    }

    private AdminProfile adminProfile(String fullName) {
        AdminProfile profile = new AdminProfile();
        profile.setFullName(fullName);
        return profile;
    }

    private UserProfile userProfile(String fullName) {
        UserProfile profile = new UserProfile();
        profile.setFullName(fullName);
        return profile;
    }

    private ChatRoomMember member(ChatRoom room, AppUser user) {
        ChatRoomMember member = new ChatRoomMember();
        member.setRoom(room);
        member.setUser(user);
        return member;
    }

    private org.springframework.test.web.servlet.request.RequestPostProcessor asUser(String username) {
        return request -> {
            org.springframework.security.core.context.SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(username, "n/a"));
            return request;
        };
    }

    @org.junit.jupiter.api.AfterEach
    void clearSecurityContext() {
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }

    /** The viewer: a dual-role admin whose name lives in the user profile. */
    private AppUser viewer() {
        AppUser user = new AppUser();
        user.setId(1L);
        user.setUsername("viewer");
        user.setEnabled(true);
        user.setRoles(new java.util.HashSet<>(Set.of(Role.USER, Role.ADMIN)));
        return user;
    }

    private AppUserRepository.ChatParticipantSummary summary(Long id, String username, String name, Long photo) {
        return new AppUserRepository.ChatParticipantSummary() {
            public Long getId() { return id; }
            public String getUsername() { return username; }
            public String getDisplayName() { return name; }
            public Long getPhotoId() { return photo; }
        };
    }

    @Test
    void participants_use_one_projection_query_and_preserve_names_photos_and_sorting() throws Exception {
        when(service.current(any())).thenReturn(viewer());
        when(users.findChatParticipants(1L)).thenReturn(List.of(
            summary(3L, "z-user", "Zara", null),
            summary(2L, "alaa.admin", "Alaa Alaleiwi", 55L)));
        when(presence.isOnline("alaa.admin")).thenReturn(true);

        mvc.perform(get("/api/chat/participants").with(asUser("viewer")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].displayName").value("Alaa Alaleiwi"))
            .andExpect(jsonPath("$[0].photoId").value(55))
            .andExpect(jsonPath("$[0].online").value(true))
            .andExpect(jsonPath("$[1].displayName").value("Zara"));
        org.mockito.Mockito.verify(users).findChatParticipants(1L);
        org.mockito.Mockito.verifyNoInteractions(profiles, adminProfiles, userService);
        org.mockito.Mockito.verify(users, org.mockito.Mockito.never()).findAll();
    }

    @Test
    void direct_room_title_uses_full_name_not_username() throws Exception {
        AppUser viewer = viewer();
        AppUser pureAdmin = adminUser();

        ChatRoom direct = new ChatRoom();
        direct.setId(9L);
        direct.setType(ChatRoom.Type.DIRECT);
        direct.setCreatedBy(pureAdmin);

        when(service.current(any())).thenReturn(viewer);
        when(service.visibleRooms(any())).thenReturn(List.of(direct));
        when(members.findByRoomIdIn(List.of(9L))).thenReturn(List.of(member(direct, viewer), member(direct, pureAdmin)));
        when(users.findChatParticipantsByIdIn(List.of(1L, 2L))).thenReturn(List.of(
            summary(1L, "viewer", "Viewer Name", null),
            summary(2L, "alaa.admin", "Alaa Alaleiwi", null)));

        mvc.perform(get("/api/chat/rooms").with(asUser("viewer")).accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].name").value("Alaa Alaleiwi"));
        org.mockito.Mockito.verifyNoInteractions(profiles, adminProfiles, userService);
        org.mockito.Mockito.verify(service, org.mockito.Mockito.never()).unreadCount(any(), any());
    }
}
