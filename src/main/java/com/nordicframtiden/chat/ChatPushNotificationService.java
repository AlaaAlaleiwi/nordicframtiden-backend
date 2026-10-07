package com.nordicframtiden.chat;

import com.nordicframtiden.security.model.AppUser;
import com.nordicframtiden.security.repo.AppUserRepository;
import com.nordicframtiden.security.repo.UserProfileRepository;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class ChatPushNotificationService {
  private final ChatPushSubscriptionRepository subscriptions;
  private final AppUserRepository users;
  private final ChatPushSender sender;
  private final UserProfileRepository profiles;
  private final com.nordicframtiden.admin.model.AdminProfileRepository adminProfiles;

  public ChatPushNotificationService(ChatPushSubscriptionRepository subscriptions,
                                     AppUserRepository users, ChatPushSender sender,
                                     UserProfileRepository profiles,
                                     com.nordicframtiden.admin.model.AdminProfileRepository adminProfiles) {
    this.subscriptions = subscriptions;
    this.users = users;
    this.sender = sender;
    this.profiles = profiles;
    this.adminProfiles = adminProfiles;
  }

  @Transactional
  public ChatPushSubscription register(Authentication authentication, String installationId) {
    AppUser user = current(authentication);
    String cleanId = requireInstallationId(installationId);
    ChatPushSubscription subscription = subscriptions.findByFirebaseInstallationId(cleanId)
        .orElseGet(ChatPushSubscription::new);
    subscription.setUser(user);
    subscription.setFirebaseInstallationId(cleanId);
    subscription.setUpdatedAt(Instant.now());
    return subscriptions.save(subscription);
  }

  @Transactional
  public void unregister(Authentication authentication, String installationId) {
    AppUser user = current(authentication);
    subscriptions.deleteByFirebaseInstallationIdAndUserId(requireInstallationId(installationId), user.getId());
  }

  @Transactional(readOnly = true)
  public void notifyNewMessage(ChatMessage message) {
    subscriptions.findForRoomExceptSender(message.getRoom().getId(), message.getSender().getId())
        .forEach(subscription -> sender.send(subscription.getFirebaseInstallationId()));
  }

  @Transactional(readOnly = true)
  public void notifyChannelDeleted(Set<String> usernames, String channelName) {
    if (usernames.isEmpty()) return;
    var data = Map.of(
        "title", "Channel deleted",
        "body", "#" + channelName + " has been deleted",
        "url", "/chat",
        "type", "channel.deleted");
    subscriptions.findByUserUsernameIn(usernames)
        .forEach(subscription -> sender.send(subscription.getFirebaseInstallationId(), data));
  }

  @Transactional(readOnly = true)
  public void notifyAdminsDocumentUploaded(String employeeName, String fileName, long userId) {
    Set<String> adminUsernames = users.findAllAdmins().stream()
        .map(AppUser::getUsername)
        .collect(Collectors.toSet());
    if (adminUsernames.isEmpty()) return;

    var data = Map.of(
        "title", "New employee document",
        "body", employeeName + " uploaded " + fileName,
        "url", "/admin/organization/users/" + userId,
        "type", "document.uploaded",
        "userId", Long.toString(userId));
    subscriptions.findByUserUsernameIn(adminUsernames)
        .forEach(subscription -> sender.send(subscription.getFirebaseInstallationId(), data));
  }

  private AppUser current(Authentication authentication) {
    if (authentication == null) throw new ChatAccessDeniedException();
    return users.findByUsername(authentication.getName()).filter(AppUser::isEnabled)
        .orElseThrow(ChatAccessDeniedException::new);
  }

  private static String requireInstallationId(String installationId) {
    String clean = installationId == null ? "" : installationId.trim();
    if (clean.isEmpty() || clean.length() > 255 || !clean.matches("[A-Za-z0-9_-]+")) {
      throw new IllegalArgumentException("Invalid Firebase installation ID");
    }
    return clean;
  }
}
