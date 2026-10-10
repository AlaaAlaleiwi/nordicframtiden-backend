package com.nordicframtiden.chat;

import com.nordicframtiden.security.model.AppUser;
import com.nordicframtiden.security.repo.AppUserRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

@Service
public class ChatService {
  private final ChatRoomRepository rooms;
  private final ChatRoomMemberRepository members;
  private final ChatMessageRepository messages;
  private final ChatReactionRepository reactions;
  private final AppUserRepository users;
  private final ChatEventPublisher events;
  private final ChatPushNotificationService notifications;
  private final ChatAttachmentRepository attachments;

  public ChatService(ChatRoomRepository rooms, ChatRoomMemberRepository members,
                     ChatMessageRepository messages, ChatReactionRepository reactions,
                     AppUserRepository users, ChatEventPublisher events,
                     ChatPushNotificationService notifications, ChatAttachmentRepository attachments) {
    this.rooms = rooms;
    this.members = members;
    this.messages = messages;
    this.reactions = reactions;
    this.users = users;
    this.events = events;
    this.notifications = notifications;
    this.attachments = attachments;
  }

  @Transactional(readOnly = true)
  public List<ChatRoom> visibleRooms(Authentication auth) {
    Long userId = current(auth).getId();
    List<ChatRoom> all = new ArrayList<>(rooms.findVisibleTo(userId));
    // Public channels the user has not joined are discoverable: they show up
    // in the room list/search ("Available to join") and can be joined from
    // there. Without this, a newly created account sees an empty chat.
    all.addAll(rooms.findDiscoverable(userId, ChatRoom.Type.CHANNEL));
    return all;
  }

  @Transactional
  public ChatRoom createChannel(Authentication auth, String name, String description,
                                boolean privateChannel, List<Long> memberIds) {
    AppUser creator = current(auth);
    String cleanName = requireText(name, 80, "Channel name").toLowerCase().replace(' ', '-');
    ChatRoom room = new ChatRoom();
    room.setType(ChatRoom.Type.CHANNEL);
    room.setName(cleanName);
    room.setDescription(cleanOptional(description, 500));
    room.setPrivateChannel(privateChannel);
    room.setCreatedBy(creator);
    room = rooms.save(room);

    LinkedHashSet<Long> ids = new LinkedHashSet<>(memberIds == null ? List.of() : memberIds);
    ids.add(creator.getId());
    for (Long id : ids) addMember(room, users.findById(id).orElseThrow(() -> new IllegalArgumentException("User not found")), id.equals(creator.getId()));
    return room;
  }

  @Transactional
  public ChatRoom direct(Authentication auth, Long otherUserId) {
    AppUser me = current(auth);
    if (me.getId().equals(otherUserId)) throw new IllegalArgumentException("Cannot message yourself");
    AppUser other = users.findById(otherUserId).filter(AppUser::isEnabled)
        .orElseThrow(() -> new IllegalArgumentException("User not found"));
    return rooms.findDirectBetween(me.getId(), otherUserId).orElseGet(() -> {
      ChatRoom room = new ChatRoom();
      room.setType(ChatRoom.Type.DIRECT);
      room.setPrivateChannel(true);
      room.setCreatedBy(me);
      room = rooms.save(room);
      addMember(room, me, false);
      addMember(room, other, false);
      return room;
    });
  }

  @Transactional
  public void join(Authentication auth, Long roomId) {
    AppUser user = current(auth);
    ChatRoom room = room(roomId);
    if (room.getType() != ChatRoom.Type.CHANNEL || room.isPrivateChannel()) throw new ChatAccessDeniedException();
    if (!members.existsByRoomIdAndUserId(roomId, user.getId())) addMember(room, user, false);
  }

  @Transactional
  public ChatRoom addChannelMembers(Authentication auth, Long roomId, List<Long> userIds) {
    AppUser user = current(auth);
    ChatRoom room = room(roomId);
    if (room.getType() != ChatRoom.Type.CHANNEL || !isChannelAdmin(roomId, user.getId())) {
      throw new ChatAccessDeniedException();
    }
    for (Long userId : new LinkedHashSet<>(userIds == null ? List.of() : userIds)) {
      if (members.existsByRoomIdAndUserId(roomId, userId)) continue;
      AppUser member = users.findById(userId).filter(AppUser::isEnabled)
          .orElseThrow(() -> new IllegalArgumentException("User not found"));
      addMember(room, member, false);
    }
    afterCommit(() -> events.publish(roomId, "channel.members.updated", roomId));
    return room;
  }

  @Transactional
  public ChatRoom addChannelAdmins(Authentication auth, Long roomId, List<Long> userIds) {
    AppUser user = current(auth);
    ChatRoom room = room(roomId);
    if (room.getType() != ChatRoom.Type.CHANNEL || !isChannelOwner(room, user.getId())) {
      throw new ChatAccessDeniedException();
    }
    for (Long userId : new LinkedHashSet<>(userIds == null ? List.of() : userIds)) {
      ChatRoomMember member = members.findByRoomIdAndUserId(roomId, userId)
          .orElseThrow(() -> new IllegalArgumentException("Channel admin must already be a member"));
      member.setChannelAdmin(true);
      members.save(member);
    }
    afterCommit(() -> events.publish(roomId, "channel.members.updated", roomId));
    return room;
  }

  @Transactional
  public ChatRoom removeChannelAdmin(Authentication auth, Long roomId, Long userId) {
    AppUser user = current(auth);
    ChatRoom room = room(roomId);
    if (room.getType() != ChatRoom.Type.CHANNEL || !isChannelOwner(room, user.getId())) {
      throw new ChatAccessDeniedException();
    }
    if (room.getCreatedBy().getId().equals(userId)) {
      throw new IllegalArgumentException("The channel owner cannot be removed as an admin");
    }
    ChatRoomMember member = members.findByRoomIdAndUserId(roomId, userId)
        .orElseThrow(() -> new IllegalArgumentException("Channel admin must already be a member"));
    member.setChannelAdmin(false);
    members.save(member);
    afterCommit(() -> events.publish(roomId, "channel.members.updated", roomId));
    return room;
  }

  @Transactional
  public void deleteChannel(Authentication auth, Long roomId) {
    AppUser user = current(auth);
    ChatRoom room = room(roomId);
    if (room.getType() != ChatRoom.Type.CHANNEL || !isChannelOwner(room, user.getId())) {
      throw new ChatAccessDeniedException();
    }
    var formerMembers = new LinkedHashSet<>(members.findUsernamesByRoomId(roomId));
    String channelName = room.getName();
    rooms.delete(room);
    // Force all cascading deletes now so a rejected deletion fails fast; the
    // notifications themselves only go out once the transaction commits.
    rooms.flush();
    afterCommit(() -> {
      events.publishTo(formerMembers, roomId, "channel.deleted", channelName);
      notifications.notifyChannelDeleted(formerMembers, channelName);
    });
  }

  @Transactional(readOnly = true)
  public List<ChatMessage> messages(Authentication auth, Long roomId, Long parentId, int limit) {
    AppUser user = current(auth);
    requireMember(roomId, user.getId());
    int safeLimit = Math.max(1, Math.min(limit, 100));
    if (parentId != null) {
      ChatMessage parent = message(parentId);
 if (!parent.getRoom().getId().equals(roomId)) throw new IllegalArgumentException("Thread is not in room");
      return messages.findByParentIdOrderByIdAsc(parentId);
    }
    List<ChatMessage> result = messages.findByRoomIdAndParentIsNullOrderByIdDesc(roomId, PageRequest.of(0, safeLimit));
    return result.reversed();
  }

  /**
   * Incremental fetch: only messages (top-level or in-thread) with id > afterId.
   * Lets clients keep a local transcript cache and pull just the delta — a
   * cache miss (afterId == null) falls back to the newest `limit` messages.
   */
  @Transactional(readOnly = true)
  public List<ChatMessage> messagesAfter(Authentication auth, Long roomId, Long parentId, Long afterId, int limit) {
    AppUser user = current(auth);
    requireMember(roomId, user.getId());
    int safeLimit = Math.max(1, Math.min(limit, 100));
    if (parentId != null) {
      ChatMessage parent = message(parentId);
      if (!parent.getRoom().getId().equals(roomId)) throw new IllegalArgumentException("Thread is not in room");
      return messages.findByParentIdOrderByIdAsc(parentId).stream()
          .filter(m -> afterId == null || m.getId() > afterId)
          .toList();
    }
    if (afterId != null) {
      return messages.findByRoomIdAndParentIsNullAndIdAfterOrderByIdAsc(roomId, afterId, PageRequest.of(0, safeLimit));
    }
    List<ChatMessage> result = messages.findByRoomIdAndParentIsNullOrderByIdDesc(roomId, PageRequest.of(0, safeLimit));
    return result.reversed();
  }

  /** Attachments of a message, metadata only (no bytes). */
  @Transactional(readOnly = true)
  public List<ChatAttachment> attachmentsOf(Long messageId) {
    return attachments.findByMessageIdOrderById(messageId);
  }

  /** Single message by id; only visible to members of its room. */
  @Transactional(readOnly = true)
  public ChatMessage messageForUser(Authentication auth, Long messageId) {
    AppUser user = current(auth);
    ChatMessage message = message(messageId);
    if (!members.existsByRoomIdAndUserId(message.getRoom().getId(), user.getId())) throw new ChatAccessDeniedException();
    return message;
  }

  /**
   * Sends a message. A message must have text, attachments, or both. When
   * attachment ids are supplied they must belong to the sender and still be
   * unbound; they are bound to the new message atomically.
   */
  @Transactional
  public ChatMessage send(Authentication auth, Long roomId, Long parentId, String body, List<Long> attachmentIds) {
    AppUser user = current(auth);
    ChatRoom room = room(roomId);
    requireMember(roomId, user.getId());
    ChatMessage parent = parentId == null ? null : message(parentId);
    if (parent != null && (!parent.getRoom().getId().equals(roomId) || parent.getParent() != null)) {
      throw new IllegalArgumentException("Invalid thread parent");
    }
    String cleanBody = body == null ? "" : body.trim();
    List<ChatAttachment> bound = java.util.Collections.emptyList();
    if (attachmentIds != null && !attachmentIds.isEmpty()) {
      bound = attachments.findByIdInAndMessageIdIsNullAndUploaderId(attachmentIds, user.getId());
      if (bound.size() != attachmentIds.size()) {
        throw new IllegalArgumentException("One or more attachments are missing or already used");
      }
    }
    if (cleanBody.isEmpty() && bound.isEmpty()) {
      throw new IllegalArgumentException("Message must contain text or an attachment");
    }

    ChatMessage result = new ChatMessage();
    result.setRoom(room);
    result.setSender(user);
    result.setParent(parent);
    result.setBody(cleanBody.isEmpty() ? "" : cleanBody);
    result = messages.save(result);
    for (ChatAttachment attachment : bound) {
      attachment.setMessageId(result.getId());
      attachments.save(attachment);
    }
    ChatMessage created = result;
    afterCommit(() -> {
      events.publish(roomId, "message.created", created.getId());
      notifications.notifyNewMessage(created);
    });
    return result;
  }

  @Transactional
  public ChatMessage edit(Authentication auth, Long messageId, String body) {
    AppUser user = current(auth);
    ChatMessage message = message(messageId);
    if (!message.getSender().getId().equals(user.getId()) || message.getDeletedAt() != null) throw new ChatAccessDeniedException();
    message.setBody(requireText(body, 4000, "Message"));
    message.setEditedAt(Instant.now());
    message = messages.save(message);
    Long roomId = message.getRoom().getId();
    Long updatedId = message.getId();
    afterCommit(() -> events.publish(roomId, "message.updated", updatedId));
    return message;
  }

  @Transactional
  public void delete(Authentication auth, Long messageId) {
    AppUser user = current(auth);
    ChatMessage message = message(messageId);
    if (!message.getSender().getId().equals(user.getId())) throw new ChatAccessDeniedException();
    message.setDeletedAt(Instant.now());
    message.setBody("Message deleted");
    messages.save(message);
    // Deleting retracts the files too: drop the attachment rows (bytes and
    // delivery rows go with them) so they are neither listed nor downloadable.
    attachments.deleteByMessageId(messageId);
    Long roomId = message.getRoom().getId();
    afterCommit(() -> events.publish(roomId, "message.deleted", messageId));
  }

  @Transactional
  public void toggleReaction(Authentication auth, Long messageId, String emoji) {
    AppUser user = current(auth);
    ChatMessage message = message(messageId);
    requireMember(message.getRoom().getId(), user.getId());
    String cleanEmoji = requireText(emoji, 32, "Emoji");
    var existing = reactions.findByMessageIdAndUserIdAndEmoji(messageId, user.getId(), cleanEmoji);
    if (existing.isPresent()) reactions.delete(existing.get());
    else {
      ChatReaction reaction = new ChatReaction();
      reaction.setMessage(message); reaction.setUser(user); reaction.setEmoji(cleanEmoji);
      reactions.save(reaction);
    }
    Long roomId = message.getRoom().getId();
    afterCommit(() -> events.publish(roomId, "reaction.changed", messageId));
  }

  @Transactional
  public void markRead(Authentication auth, Long roomId, Long messageId) {
    AppUser user = current(auth);
    ChatRoomMember member = members.findByRoomIdAndUserId(roomId, user.getId()).orElseThrow(ChatAccessDeniedException::new);
    if (messageId != null && !message(messageId).getRoom().getId().equals(roomId)) throw new IllegalArgumentException("Message is not in room");
    member.setLastReadMessageId(messageId);
    members.save(member);
  }

  @Transactional(readOnly = true)
  public long unreadCount(Long roomId, Long userId) {
    return members.findByRoomIdAndUserId(roomId, userId)
        .map(member -> messages.countUnread(roomId, member.getLastReadMessageId() == null ? 0 : member.getLastReadMessageId(), userId))
        .orElse(0L);
  }

  public AppUser current(Authentication auth) {
    return users.findByUsername(auth.getName()).filter(AppUser::isEnabled)
        .orElseThrow(() -> new ChatAccessDeniedException());
  }

  /**
   * Runs websocket/push side effects only once the surrounding transaction
   * has committed, so clients never see (or refetch) uncommitted or
   * rolled-back state. Without an active transaction it runs immediately.
   */
  private static void afterCommit(Runnable action) {
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      action.run();
      return;
    }
    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
      @Override public void afterCommit() { action.run(); }
    });
  }

  private void addMember(ChatRoom room, AppUser user, boolean channelAdmin) {
    ChatRoomMember member = new ChatRoomMember(); member.setRoom(room); member.setUser(user);
    member.setChannelAdmin(channelAdmin); members.save(member);
  }
  private boolean isChannelAdmin(Long roomId, Long userId) {
    return members.findByRoomIdAndUserId(roomId, userId).map(ChatRoomMember::isChannelAdmin).orElse(false);
  }
  private boolean isChannelOwner(ChatRoom room, Long userId) {
    return room.getCreatedBy().getId().equals(userId);
  }
  private void requireMember(Long roomId, Long userId) {
    if (!members.existsByRoomIdAndUserId(roomId, userId)) throw new ChatAccessDeniedException();
  }
  private ChatRoom room(Long id) { return rooms.findById(id).orElseThrow(() -> new IllegalArgumentException("Room not found")); }
  private ChatMessage message(Long id) { return messages.findById(id).orElseThrow(() -> new IllegalArgumentException("Message not found")); }
  private static String requireText(String value, int max, String field) {
    String clean = value == null ? "" : value.trim();
    if (clean.isEmpty() || clean.length() > max) throw new IllegalArgumentException(field + " must contain 1-" + max + " characters");
    return clean;
  }
  private static String cleanOptional(String value, int max) {
    if (value == null || value.isBlank()) return null;
    String clean = value.trim();
    if (clean.length() > max) throw new IllegalArgumentException("Value is too long");
    return clean;
  }
}
