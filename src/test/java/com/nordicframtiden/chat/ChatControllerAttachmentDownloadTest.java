package com.nordicframtiden.chat;

import com.nordicframtiden.admin.model.AdminProfileRepository;
import com.nordicframtiden.security.model.AppUser;
import com.nordicframtiden.security.repo.AppUserRepository;
import com.nordicframtiden.security.repo.UserProfileRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.when;

/**
 * The stored content type is client-supplied. A malformed value must not
 * turn a download into a 500 after the delivery was recorded and the bytes
 * possibly purged — the requester still has to receive the payload.
 */
@ExtendWith(MockitoExtension.class)
class ChatControllerAttachmentDownloadTest {

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

  @Test
  void malformedContentTypeFallsBackToOctetStreamAndStillDelivers() {
    AppUser me = new AppUser();
    me.setId(7L);
    me.setUsername("anna");
    ChatAttachment attachment = new ChatAttachment();
    attachment.setId(55L);
    attachment.setMessageId(99L);
    attachment.setFileName("report.pdf");
    attachment.setContentType("not a media type");
    attachment.setData(new byte[] {1, 2, 3});
    var auth = new UsernamePasswordAuthenticationToken("anna", null);

    when(attachments.findById(55L)).thenReturn(Optional.of(attachment));
    when(service.current(any())).thenReturn(me);

    var response = controller.download(auth, 55L);

    assertThat(response.getStatusCode().value()).isEqualTo(200);
    assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_OCTET_STREAM);
    assertThat(response.getBody()).containsExactly(1, 2, 3);
    InOrder order = inOrder(deliveries, purgeService);
    order.verify(deliveries).save(any());
    order.verify(purgeService).purgeIfFullyDelivered(55L);
  }

  @Test
  void safeMediaTypeKeepsValidTypes() {
    assertThat(ChatController.safeMediaType("image/png")).isEqualTo(MediaType.IMAGE_PNG);
    assertThat(ChatController.safeMediaType("")).isEqualTo(MediaType.APPLICATION_OCTET_STREAM);
    assertThat(ChatController.safeMediaType("image/")).isEqualTo(MediaType.APPLICATION_OCTET_STREAM);
  }
}
