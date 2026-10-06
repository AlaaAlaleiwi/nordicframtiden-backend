package com.nordicframtiden.chat;

import com.nordicframtiden.admin.model.AdminProfileRepository;
import com.nordicframtiden.security.model.AppUser;
import com.nordicframtiden.security.service.UserService;
import com.nordicframtiden.security.repo.AppUserRepository;
import com.nordicframtiden.security.repo.UserProfileRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/chat")
public class ChatController {
  private static final long MAX_UPLOAD_BYTES = 10L * 1024 * 1024; // 10 MB

  private final ChatService service;
  private final ChatRoomMemberRepository members;
  private final ChatMessageRepository messages;
  private final ChatReactionRepository reactions;
  private final ChatAttachmentRepository attachments;
  private final ChatAttachmentDeliveryRepository deliveries;
  private final ChatAttachmentPurgeService purgeService;
  private final AppUserRepository users;
  private final UserProfileRepository profiles;
  private final AdminProfileRepository adminProfiles;
  private final ChatPresence presence;
  private final UserService userService;

  public ChatController(ChatService service, ChatRoomMemberRepository members,
                        ChatMessageRepository messages, ChatReactionRepository reactions,
                        ChatAttachmentRepository attachments,
                        ChatAttachmentDeliveryRepository deliveries,
                        ChatAttachmentPurgeService purgeService,
                        AppUserRepository users, UserProfileRepository profiles,
                        AdminProfileRepository adminProfiles, ChatPresence presence,
                        UserService userService) {
    this.service = service; this.members = members; this.messages = messages; this.reactions = reactions;
    this.attachments = attachments;
    this.deliveries = deliveries;
    this.purgeService = purgeService;
    this.users = users; this.profiles = profiles; this.adminProfiles = adminProfiles; this.presence = presence;
    this.userService = userService;
  }

  public record ParticipantDto(Long id, String username, String displayName, boolean online, Long photoId) {}
  public record RoomDto(Long id, String type, String name, String description, boolean privateChannel,
                        boolean member, boolean canManage, boolean owner, Long ownerUserId,
                        long unreadCount, List<ParticipantDto> participants,
                        List<Long> adminUserIds) {}
  public record ReactionDto(String emoji, long count, boolean mine) {}
  public record AttachmentDto(Long id, String fileName, String contentType, long sizeBytes) {}
  public record MessageDto(Long id, Long roomId, Long parentId, ParticipantDto sender, String body,
                           Instant createdAt, Instant editedAt, boolean deleted, long replyCount,
                           List<ReactionDto> reactions, List<AttachmentDto> attachments) {}
  public record CreateChannelRequest(@NotBlank @Size(max=80) String name, @Size(max=500) String description,
                                     boolean privateChannel, List<Long> memberIds) {}
  public record AddChannelMembersRequest(@NotEmpty List<@NotNull Long> userIds) {}
  public record AddChannelAdminsRequest(@NotEmpty List<@NotNull Long> userIds) {}
  public record DirectRequest(@NotNull Long userId) {}
  public record SendMessageRequest(Long parentId, @Size(max=4000) String body, List<Long> attachmentIds) {}
  public record EditMessageRequest(@NotBlank @Size(max=4000) String body) {}
  public record ReactionRequest(@NotBlank @Size(max=32) String emoji) {}
  public record ReadRequest(Long messageId) {}

  @GetMapping("/participants")
  public List<ParticipantDto> participants(Authentication auth) {
    Long me = service.current(auth).getId();
    return users.findChatParticipants(me).stream()
        .map(this::participant).sorted(Comparator.comparing(ParticipantDto::displayName, String.CASE_INSENSITIVE_ORDER)).toList();
  }

  @GetMapping("/rooms")
  public List<RoomDto> rooms(Authentication auth) {
    AppUser me = service.current(auth);
    List<ChatRoom> visible = service.visibleRooms(auth);
    if (visible.isEmpty()) return List.of();
    List<Long> roomIds = visible.stream().map(ChatRoom::getId).toList();
    List<ChatRoomMember> memberships = members.findByRoomIdIn(roomIds);
    Map<Long, List<ChatRoomMember>> byRoom = memberships.stream()
        .collect(Collectors.groupingBy(member -> member.getRoom().getId()));
    List<Long> userIds = memberships.stream().map(member -> member.getUser().getId()).distinct().toList();
    Map<Long, ParticipantDto> people = userIds.isEmpty() ? Map.of() : users.findChatParticipantsByIdIn(userIds)
        .stream().map(this::participant).collect(Collectors.toMap(ParticipantDto::id, person -> person));
    Map<Long, Long> unread = messages.countUnreadByRoomIds(roomIds, me.getId()).stream()
        .collect(Collectors.toMap(ChatMessageRepository.RoomUnreadCount::getRoomId,
            ChatMessageRepository.RoomUnreadCount::getUnreadCount));
    return visible.stream().map(room -> room(room, me, byRoom.getOrDefault(room.getId(), List.of()),
        member -> people.get(member.getUser().getId()), unread.getOrDefault(room.getId(), 0L))).toList();
  }

  @PostMapping("/channels") @ResponseStatus(HttpStatus.CREATED)
  public RoomDto createChannel(Authentication auth, @Valid @RequestBody CreateChannelRequest request) {
    return room(service.createChannel(auth, request.name(), request.description(), request.privateChannel(), request.memberIds()), service.current(auth));
  }

  @PostMapping("/direct")
  public RoomDto direct(Authentication auth, @Valid @RequestBody DirectRequest request) {
    return room(service.direct(auth, request.userId()), service.current(auth));
  }

  @PostMapping("/rooms/{roomId}/join") @ResponseStatus(HttpStatus.NO_CONTENT)
  public void join(Authentication auth, @PathVariable Long roomId) { service.join(auth, roomId); }

  @PostMapping("/channels/{roomId}/members")
  public RoomDto addChannelMembers(Authentication auth, @PathVariable Long roomId,
                                   @Valid @RequestBody AddChannelMembersRequest request) {
    return room(service.addChannelMembers(auth, roomId, request.userIds()), service.current(auth));
  }

  @PostMapping("/channels/{roomId}/admins")
  public RoomDto addChannelAdmins(Authentication auth, @PathVariable Long roomId,
                                  @Valid @RequestBody AddChannelAdminsRequest request) {
    return room(service.addChannelAdmins(auth, roomId, request.userIds()), service.current(auth));
  }

  @DeleteMapping("/channels/{roomId}/admins/{userId}")
  public RoomDto removeChannelAdmin(Authentication auth, @PathVariable Long roomId,
                                    @PathVariable Long userId) {
    return room(service.removeChannelAdmin(auth, roomId, userId), service.current(auth));
  }

  @DeleteMapping("/channels/{roomId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void deleteChannel(Authentication auth, @PathVariable Long roomId) {
    service.deleteChannel(auth, roomId);
  }

  @GetMapping("/rooms/{roomId}/messages")
  public List<MessageDto> messages(Authentication auth, @PathVariable Long roomId,
                                   @RequestParam(required=false) Long parentId,
                                   @RequestParam(required=false) Long afterId,
                                   @RequestParam(defaultValue="50") int limit) {
    AppUser me = service.current(auth);
    List<ChatMessage> loaded = afterId == null
        ? service.messages(auth, roomId, parentId, limit)
        : service.messagesAfter(auth, roomId, parentId, afterId, limit);
    return loaded.stream().map(message -> message(message, me)).toList();
  }

  @GetMapping("/messages/{messageId}")
  public MessageDto messageById(Authentication auth, @PathVariable Long messageId) {
    AppUser me = service.current(auth);
    return message(service.messageForUser(auth, messageId), me);
  }

  @PostMapping("/rooms/{roomId}/messages") @ResponseStatus(HttpStatus.CREATED)
  public MessageDto send(Authentication auth, @PathVariable Long roomId, @Valid @RequestBody SendMessageRequest request) {
    AppUser me = service.current(auth);
    return message(service.send(auth, roomId, request.parentId(), request.body(), request.attachmentIds()), me);
  }

  /** Uploads a file; returns its id to reference when sending the message. */
  @PostMapping("/attachments") @ResponseStatus(HttpStatus.CREATED)
  public AttachmentDto upload(Authentication auth,
      @RequestParam("file") MultipartFile file) throws IOException {
    AppUser me = service.current(auth);
    if (file.isEmpty()) throw new IllegalArgumentException("File is empty");
    if (file.getSize() > MAX_UPLOAD_BYTES) throw new IllegalArgumentException("File exceeds the 10 MB limit");
    ChatAttachment attachment = new ChatAttachment();
    attachment.setUploaderId(me.getId());
    String name = file.getOriginalFilename();
    attachment.setFileName(name == null || name.isBlank() ? "file" : name);
    String type = file.getContentType();
    attachment.setContentType(type == null || type.isBlank() ? "application/octet-stream" : type);
    attachment.setSizeBytes(file.getSize());
    attachment.setData(file.getBytes());
    return attachmentDto(attachments.save(attachment));
  }

  /** Streams the attachment bytes; only members of the carrying message's room may download. */
  @GetMapping("/attachments/{attachmentId}")
  public ResponseEntity<byte[]> download(Authentication auth, @PathVariable Long attachmentId) {
    ChatAttachment attachment = attachments.findById(attachmentId)
        .orElseThrow(() -> new IllegalArgumentException("Attachment not found"));
    if (attachment.getMessageId() == null) throw new IllegalArgumentException("Attachment not found");
    service.messageForUser(auth, attachment.getMessageId());
    AppUser me = service.current(auth);

    // Capture the bytes BEFORE any purge — the requester must always get
    // the payload they came for, even in a single-member room where this
    // download itself completes the delivery set.
    byte[] data = attachment.getData();
    boolean purged = attachment.getPurgedAt() != null;
    if (purged || data == null || data.length == 0) {
      // Bytes already wiped: the receiving client has them cached (or the
      // retention window passed). Metadata remains for display.
      return ResponseEntity.status(HttpStatus.GONE)
          .contentType(MediaType.APPLICATION_JSON)
          .body("{\"error\":\"Attachment bytes no longer available\"}".getBytes());
    }

    // Record delivery for this member, then purge if everyone has received
    // the attachment (this request included).
    if (!deliveries.existsByAttachmentIdAndUserId(attachmentId, me.getId())) {
      ChatAttachmentDelivery delivery = new ChatAttachmentDelivery();
      delivery.setAttachmentId(attachmentId);
      delivery.setUserId(me.getId());
      deliveries.save(delivery);
    }
    purgeService.purgeIfFullyDelivered(attachmentId);

    return ResponseEntity.ok()
        .header("Content-Disposition", "attachment; filename=\"" + attachment.getFileName().replace("\"", "") + "\"")
        .contentType(MediaType.parseMediaType(attachment.getContentType()))
        .body(data);
  }

  @PutMapping("/messages/{messageId}")
  public MessageDto edit(Authentication auth, @PathVariable Long messageId, @Valid @RequestBody EditMessageRequest request) {
    AppUser me = service.current(auth);
    return message(service.edit(auth, messageId, request.body()), me);
  }

  @DeleteMapping("/messages/{messageId}") @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete(Authentication auth, @PathVariable Long messageId) { service.delete(auth, messageId); }

  @PostMapping("/messages/{messageId}/reactions") @ResponseStatus(HttpStatus.NO_CONTENT)
  public void reaction(Authentication auth, @PathVariable Long messageId, @Valid @RequestBody ReactionRequest request) {
    service.toggleReaction(auth, messageId, request.emoji());
  }

  @PostMapping("/rooms/{roomId}/read") @ResponseStatus(HttpStatus.NO_CONTENT)
  public void read(Authentication auth, @PathVariable Long roomId, @RequestBody ReadRequest request) {
    service.markRead(auth, roomId, request.messageId());
  }

  private RoomDto room(ChatRoom room, AppUser me) {
    List<ChatRoomMember> memberships = members.findByRoomId(room.getId());
    boolean isMember = memberships.stream().anyMatch(member -> member.getUser().getId().equals(me.getId()));
    return room(room, me, memberships, member -> participant(member.getUser()),
        isMember ? service.unreadCount(room.getId(), me.getId()) : 0);
  }

  private RoomDto room(ChatRoom room, AppUser me, List<ChatRoomMember> memberships,
                       java.util.function.Function<ChatRoomMember, ParticipantDto> mapper, long unreadCount) {
    List<ParticipantDto> roomParticipants = memberships.stream().map(mapper).toList();
    boolean isMember = roomParticipants.stream().anyMatch(person -> person.id().equals(me.getId()));
    String displayName = room.getName();
    if (room.getType() == ChatRoom.Type.DIRECT) {
      displayName = roomParticipants.stream().filter(person -> !person.id().equals(me.getId()))
          .map(ParticipantDto::displayName).findFirst().orElse("Direct message");
    }
    List<Long> adminUserIds = memberships.stream().filter(ChatRoomMember::isChannelAdmin)
        .map(member -> member.getUser().getId()).toList();
    boolean owner = room.getType() == ChatRoom.Type.CHANNEL && room.getCreatedBy().getId().equals(me.getId());
    boolean canManage = room.getType() == ChatRoom.Type.CHANNEL
        && (owner || adminUserIds.contains(me.getId()));
    return new RoomDto(room.getId(), room.getType().name(), displayName, room.getDescription(),
        room.isPrivateChannel(), isMember, canManage, owner, room.getCreatedBy().getId(),
        isMember ? unreadCount : 0, roomParticipants, adminUserIds);
  }

  private MessageDto message(ChatMessage message, AppUser me) {
    Map<String, List<ChatReaction>> grouped = reactions.findByMessageId(message.getId()).stream()
        .collect(Collectors.groupingBy(ChatReaction::getEmoji, LinkedHashMap::new, Collectors.toList()));
    List<ReactionDto> reactionDtos = grouped.entrySet().stream()
        .map(entry -> new ReactionDto(entry.getKey(), entry.getValue().size(),
            entry.getValue().stream().anyMatch(reaction -> reaction.getUser().getId().equals(me.getId())))).toList();
    List<AttachmentDto> attachmentDtos = attachments.findByMessageIdOrderById(message.getId()).stream()
        .map(this::attachmentDto).toList();
    return new MessageDto(message.getId(), message.getRoom().getId(),
        message.getParent() == null ? null : message.getParent().getId(), participant(message.getSender()),
        message.getBody(), message.getCreatedAt(), message.getEditedAt(), message.getDeletedAt() != null,
        messages.countByParentId(message.getId()), reactionDtos, attachmentDtos);
  }

  private AttachmentDto attachmentDto(ChatAttachment attachment) {
    return new AttachmentDto(attachment.getId(), attachment.getFileName(),
        attachment.getContentType(), attachment.getSizeBytes());
  }

  private ParticipantDto participant(AppUser user) {
    // Full name from the user profile; pure admins keep theirs in the admin
    // profile instead, so consult that before degrading to the username.
    String displayName = profiles.findByUserId(user.getId()).map(profile -> profile.getFullName()).filter(name -> !name.isBlank())
        .or(() -> adminProfiles.findByUserId(user.getId()).map(profile -> profile.getFullName()).filter(name -> !name.isBlank()))
        .orElse(user.getUsername());
    return new ParticipantDto(user.getId(), user.getUsername(), displayName, presence.isOnline(user.getUsername()),
        user.getPhotoId());
  }

  private ParticipantDto participant(AppUserRepository.ChatParticipantSummary user) {
    return new ParticipantDto(user.getId(), user.getUsername(), user.getDisplayName(),
        presence.isOnline(user.getUsername()), user.getPhotoId());
  }
}
