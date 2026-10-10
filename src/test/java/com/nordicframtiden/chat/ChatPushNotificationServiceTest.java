package com.nordicframtiden.chat;

import com.nordicframtiden.security.model.AppUser;
import com.nordicframtiden.security.repo.AppUserRepository;
import com.nordicframtiden.security.repo.UserProfileRepository;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatPushNotificationServiceTest {

  @Test
  void registersInstallationForAuthenticatedUser() {
    ChatPushSubscriptionRepository subscriptions = mock(ChatPushSubscriptionRepository.class);
    AppUserRepository users = mock(AppUserRepository.class);
    ChatPushSender sender = mock(ChatPushSender.class);
    AppUser user = user(7L, "anna");
    when(users.findByUsername("anna")).thenReturn(Optional.of(user));
    when(subscriptions.findByFirebaseInstallationId("fid-123"))
        .thenReturn(Optional.empty());
    when(subscriptions.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    ChatPushNotificationService service = new ChatPushNotificationService(
        subscriptions, users, sender, mock(UserProfileRepository.class),
        mock(com.nordicframtiden.admin.model.AdminProfileRepository.class));

    ChatPushSubscription saved = service.register(authentication("anna"), "fid-123");

    assertThat(saved.getUser()).isSameAs(user);
    assertThat(saved.getFirebaseInstallationId()).isEqualTo("fid-123");
  }

  @Test
  void reRegisteringAnotherUsersInstallationMovesTheSameRowInsteadOfDuplicatingIt() {
    ChatPushSubscriptionRepository subscriptions = mock(ChatPushSubscriptionRepository.class);
    AppUserRepository users = mock(AppUserRepository.class);
    AppUser erik = user(8L, "erik");
    when(users.findByUsername("erik")).thenReturn(Optional.of(erik));
    ChatPushSubscription previous = subscription("fid-shared");
    previous.setUser(user(7L, "anna"));
    when(subscriptions.findByFirebaseInstallationId("fid-shared")).thenReturn(Optional.of(previous));
    when(subscriptions.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    ChatPushNotificationService service = new ChatPushNotificationService(
        subscriptions, users, mock(ChatPushSender.class), mock(UserProfileRepository.class),
        mock(com.nordicframtiden.admin.model.AdminProfileRepository.class));

    ChatPushSubscription saved = service.register(authentication("erik"), "fid-shared");

    // The device changed hands: the one row (unique installation id) now
    // belongs to the new user, so the previous owner stops receiving pushes.
    assertThat(saved).isSameAs(previous);
    assertThat(saved.getUser()).isSameAs(erik);
    verify(subscriptions).save(previous);
  }

  @Test
  void sendsNewMessageNotificationToOtherRoomMembersOnly() {
    ChatPushSubscriptionRepository subscriptions = mock(ChatPushSubscriptionRepository.class);
    AppUserRepository users = mock(AppUserRepository.class);
    ChatPushSender sender = mock(ChatPushSender.class);
    ChatPushNotificationService service = new ChatPushNotificationService(
        subscriptions, users, sender, mock(UserProfileRepository.class),
        mock(com.nordicframtiden.admin.model.AdminProfileRepository.class));
    ChatRoom room = new ChatRoom();
    room.setId(12L);
    ChatMessage message = new ChatMessage();
    message.setId(99L);
    message.setRoom(room);
    message.setSender(user(7L, "anna"));
    message.setBody("Hello team");
    when(subscriptions.findForRoomExceptSender(12L, 7L))
        .thenReturn(List.of(subscription("fid-erik"), subscription("fid-sara")));

    service.notifyNewMessage(message);

    verify(sender).send("fid-erik");
    verify(sender).send("fid-sara");
  }

  @Test
  void employeeDocumentUploadNotifiesEverySubscribedAdmin() {
    ChatPushSubscriptionRepository subscriptions = mock(ChatPushSubscriptionRepository.class);
    AppUserRepository users = mock(AppUserRepository.class);
    ChatPushSender sender = mock(ChatPushSender.class);
    AppUser firstAdmin = user(1L, "first.admin");
    AppUser secondAdmin = user(2L, "second.admin");
    when(users.findAllAdmins()).thenReturn(List.of(firstAdmin, secondAdmin));
    when(subscriptions.findByUserUsernameIn(Set.of("first.admin", "second.admin")))
        .thenReturn(List.of(subscription("fid-first"), subscription("fid-second")));

    ChatPushNotificationService service = new ChatPushNotificationService(
        subscriptions, users, sender, mock(UserProfileRepository.class),
        mock(com.nordicframtiden.admin.model.AdminProfileRepository.class));
    service.notifyAdminsDocumentUploaded("Anna Andersson", "license.pdf", 7L);

    @SuppressWarnings("unchecked")
    org.mockito.ArgumentCaptor<java.util.Map<String, String>> captor =
        org.mockito.ArgumentCaptor.forClass((Class) java.util.Map.class);
    verify(sender).send(org.mockito.Mockito.eq("fid-first"), captor.capture());
    verify(sender).send(org.mockito.Mockito.eq("fid-second"), org.mockito.Mockito.anyMap());
    assertThat(captor.getValue())
        .containsEntry("type", "document.uploaded")
        .containsEntry("userId", "7")
        .containsEntry("body", "Anna Andersson uploaded license.pdf");
  }

  private static ChatPushSubscription subscription(String fid) {
    ChatPushSubscription subscription = new ChatPushSubscription();
    subscription.setFirebaseInstallationId(fid);
    return subscription;
  }

  private static UsernamePasswordAuthenticationToken authentication(String username) {
    return new UsernamePasswordAuthenticationToken(username, null);
  }

  private static AppUser user(Long id, String username) {
    AppUser user = new AppUser();
    user.setId(id);
    user.setUsername(username);
    user.setEnabled(true);
    return user;
  }
}
