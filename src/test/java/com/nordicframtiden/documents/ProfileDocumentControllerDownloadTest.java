package com.nordicframtiden.documents;

import com.nordicframtiden.chat.ChatPushNotificationService;
import com.nordicframtiden.security.AccountAuthorization;
import com.nordicframtiden.security.model.AppUser;
import com.nordicframtiden.security.repo.AppUserRepository;
import com.nordicframtiden.security.repo.UserProfileRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/** A malformed stored (client-supplied) content type must not turn a download into a 500. */
@ExtendWith(MockitoExtension.class)
class ProfileDocumentControllerDownloadTest {

  @Mock ProfileDocumentRepository documents;
  @Mock DocumentEncryptionService encryption;
  @Mock AppUserRepository users;
  @Mock UserProfileRepository profiles;
  @Mock AccountAuthorization authorization;
  @Mock ChatPushNotificationService pushNotifications;

  @InjectMocks ProfileDocumentController controller;

  @Test
  void malformedContentTypeFallsBackToOctetStream() {
    AppUser owner = new AppUser();
    owner.setId(5L);
    ProfileDocument doc = new ProfileDocument();
    doc.setUser(owner);
    doc.setFileName("contract.pdf");
    doc.setContentType("garbage;;=");
    doc.setIv(new byte[] {9});
    doc.setData(new byte[] {8});
    var auth = new UsernamePasswordAuthenticationToken("admin", null);

    when(authorization.canViewDocuments(auth, 5L)).thenReturn(true);
    when(authorization.canManage(auth, 5L)).thenReturn(true);
    when(documents.findById(3L)).thenReturn(Optional.of(doc));
    when(encryption.decrypt(any(), eq(doc.getData()))).thenReturn(new byte[] {1, 2});

    var response = controller.download(5L, 3L, auth);

    assertThat(response.getStatusCode().value()).isEqualTo(200);
    assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_OCTET_STREAM);
    assertThat(response.getBody()).containsExactly(1, 2);
  }
}
