package com.nordicframtiden.documents;

import com.nordicframtiden.security.model.AppUser;
import com.nordicframtiden.security.repo.AppUserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.io.IOException;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProfilePhotoServiceTest {

  private ProfileDocumentRepository documents;
  private DocumentEncryptionService encryption;
  private AppUserRepository users;
  private ProfilePhotoService service;

  private AppUser owner;

  @BeforeEach
  void setUp() {
    documents = Mockito.mock(ProfileDocumentRepository.class);
    encryption = Mockito.mock(DocumentEncryptionService.class);
    users = Mockito.mock(AppUserRepository.class);

    service = new ProfilePhotoService(documents, encryption, users);

    owner = new AppUser();
    owner.setId(7L);
    owner.setUsername("anna");
  }

  private org.springframework.mock.web.MockMultipartFile image(String name, String contentType, byte[] bytes) {
    return new org.springframework.mock.web.MockMultipartFile("file", name, contentType, bytes);
  }

  private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 1, 2};
  private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 4, 5};
  private static final byte[] WEBP = {'R', 'I', 'F', 'F', 4, 0, 0, 0, 'W', 'E', 'B', 'P', 'V', 'P', '8', ' '};

  private ProfileDocument savedDocument(long id) {
    ProfileDocument d = new ProfileDocument();
    org.springframework.test.util.ReflectionTestUtils.setField(d, "id", id);
    return d;
  }

  @Test
  void uploadStoresEncryptedDocumentAndLinksItOnTheUser() throws IOException {
    when(users.findById(7L)).thenReturn(Optional.of(owner));
    when(encryption.encrypt(any())).thenReturn(new DocumentEncryptionService.Sealed(new byte[]{9, 8, 7}, new byte[]{1, 2, 3, 4}));
    when(documents.save(any(ProfileDocument.class))).thenReturn(savedDocument(55L));

    ProfilePhotoService.PhotoDto dto = service.upload(
        7L, image("me.png", "image/png", PNG));

    assertEquals(55L, dto.documentId());
    assertEquals(55L, owner.getPhotoId());
    assertNotNull(owner.getPhotoUpdatedAt());

    ArgumentCaptor<ProfileDocument> doc = ArgumentCaptor.forClass(ProfileDocument.class);
    verify(documents).save(doc.capture());
    assertEquals("image/png", doc.getValue().getContentType());
    assertEquals("me.png", doc.getValue().getFileName());
    assertArrayEquals(new byte[]{9, 8, 7}, doc.getValue().getIv());
    assertArrayEquals(new byte[]{1, 2, 3, 4}, doc.getValue().getData());
  }

  @Test
  void uploadReplacesPreviousPhotoDocument() throws IOException {
    owner.setPhotoId(10L);
    owner.setPhotoUpdatedAt(Instant.now());
    when(users.findById(7L)).thenReturn(Optional.of(owner));
    when(encryption.encrypt(any())).thenReturn(new DocumentEncryptionService.Sealed(new byte[]{1}, new byte[]{2}));
    when(documents.save(any(ProfileDocument.class))).thenReturn(savedDocument(56L));

    service.upload(7L, image("me.jpg", "image/jpeg", JPEG));

    verify(documents).deleteByIdAndUserId(10L, 7L);
    assertEquals(56L, owner.getPhotoId());
  }

  @Test
  void uploadRejectsEmptyFile() {
    when(users.findById(7L)).thenReturn(Optional.of(owner));

    assertThrows(IllegalArgumentException.class,
        () -> service.upload(7L, image("x.png", "image/png", new byte[0])));
    verify(documents, never()).save(any(ProfileDocument.class));
  }

  @Test
  void uploadRejectsNonImageContentType() {
    when(users.findById(7L)).thenReturn(Optional.of(owner));

    assertThrows(IllegalArgumentException.class,
        () -> service.upload(7L, image("x.pdf", "application/pdf", "%PDF-1.7".getBytes())));
    verify(documents, never()).save(any(ProfileDocument.class));
  }

  @Test
  void uploadRejectsSvgAndOtherImagesWhoseBytesAreNotAllowedRasterFormats() {
    when(users.findById(7L)).thenReturn(Optional.of(owner));
    byte[] svg = "<svg xmlns=\"http://www.w3.org/2000/svg\"><script>alert(1)</script></svg>".getBytes();

    assertThrows(IllegalArgumentException.class,
        () -> service.upload(7L, image("x.svg", "image/svg+xml", svg)));
    // Claiming a raster type does not help: the signature decides.
    assertThrows(IllegalArgumentException.class,
        () -> service.upload(7L, image("x.png", "image/png", svg)));
    assertThrows(IllegalArgumentException.class,
        () -> service.upload(7L, image("x.gif", "image/gif", "GIF89a....".getBytes())));
    verify(documents, never()).save(any(ProfileDocument.class));
  }

  @Test
  void uploadStoresTypeDetectedFromSignatureNotTheClaimedOne() throws IOException {
    when(users.findById(7L)).thenReturn(Optional.of(owner));
    when(encryption.encrypt(any())).thenReturn(new DocumentEncryptionService.Sealed(new byte[]{1}, new byte[]{2}));
    when(documents.save(any(ProfileDocument.class))).thenReturn(savedDocument(58L));

    service.upload(7L, image("me.webp", "application/octet-stream", WEBP));

    ArgumentCaptor<ProfileDocument> doc = ArgumentCaptor.forClass(ProfileDocument.class);
    verify(documents).save(doc.capture());
    assertEquals("image/webp", doc.getValue().getContentType());
  }

  @Test
  void loadNeverServesLegacyNonRasterPhotoAsImage() {
    owner.setPhotoId(55L);
    owner.setPhotoUpdatedAt(Instant.ofEpochMilli(1L));
    when(users.findById(7L)).thenReturn(Optional.of(owner));
    ProfileDocument doc = savedDocument(55L);
    doc.setUser(owner);
    doc.setContentType("image/svg+xml");
    doc.setFileName("legacy.svg");
    doc.setIv(new byte[]{1});
    doc.setData(new byte[]{2});
    when(documents.findById(55L)).thenReturn(Optional.of(doc));
    when(encryption.decrypt(any(), any())).thenReturn("<svg/>".getBytes());

    assertEquals("application/octet-stream", service.load(7L, null).contentType());
  }

  @Test
  void uploadRejectsFileAboveLimit() {
    when(users.findById(7L)).thenReturn(Optional.of(owner));
    byte[] big = new byte[(int) (ProfilePhotoService.MAX_PHOTO_BYTES + 1)];

    assertThrows(IllegalArgumentException.class,
        () -> service.upload(7L, image("x.png", "image/png", big)));
    verify(documents, never()).save(any(ProfileDocument.class));
  }

  @Test
  void uploadFailsWhenUserMissing() {
    when(users.findById(7L)).thenReturn(Optional.empty());

    assertThrows(IllegalArgumentException.class,
        () -> service.upload(7L, image("x.png", "image/png", PNG)));
    verify(documents, never()).save(any(ProfileDocument.class));
  }

  @Test
  void loadReturnsDecryptedBytesAndMetadata() throws IOException {
    owner.setPhotoId(55L);
    owner.setPhotoUpdatedAt(Instant.ofEpochMilli(123456L));
    when(users.findById(7L)).thenReturn(Optional.of(owner));

    ProfileDocument doc = savedDocument(55L);
    doc.setUser(owner);
    doc.setContentType("image/png");
    doc.setFileName("me.png");
    doc.setIv(new byte[]{1});
    doc.setData(new byte[]{2});
    when(documents.findById(55L)).thenReturn(Optional.of(doc));
    when(encryption.decrypt(any(), any())).thenReturn(PNG);

    ProfilePhotoService.PhotoData photo = service.load(7L, null);

    assertArrayEquals(PNG, photo.bytes());
    assertEquals("image/png", photo.contentType());
    assertEquals("me.png", photo.fileName());
    assertEquals("123456", photo.version());
  }

  @Test
  void loadThrowsWhenPhotoNotSet() {
    when(users.findById(7L)).thenReturn(Optional.of(owner));

    assertThrows(IllegalArgumentException.class, () -> service.load(7L, null));
  }

  @Test
  void loadThrowsWhenDocumentMissingOrForeign() {
    owner.setPhotoId(55L);
    when(users.findById(7L)).thenReturn(Optional.of(owner));
    when(documents.findById(55L)).thenReturn(Optional.empty());

    assertThrows(IllegalArgumentException.class, () -> service.load(7L, null));
  }

  @Test
  void clearRemovesLinkAndDeletesDocument() {
    owner.setPhotoId(55L);
    owner.setPhotoUpdatedAt(Instant.now());
    when(users.findById(7L)).thenReturn(Optional.of(owner));

    service.clear(7L);

    assertNull(owner.getPhotoId());
    assertNull(owner.getPhotoUpdatedAt());
    verify(users).save(owner);
    verify(documents).deleteByIdAndUserId(55L, 7L);
  }

  @Test
  void versionChangesWhenPhotoChanges() throws IOException {
    when(users.findById(7L)).thenReturn(Optional.of(owner));
    when(encryption.encrypt(any())).thenReturn(new DocumentEncryptionService.Sealed(new byte[]{1}, new byte[]{2}));
    when(documents.save(any(ProfileDocument.class))).thenReturn(savedDocument(57L));

    service.upload(7L, image("a.png", "image/png", PNG));
    Instant first = owner.getPhotoUpdatedAt();

    String v1 = service.version(owner);
    owner.setPhotoUpdatedAt(first.plusMillis(5));
    String v2 = service.version(owner);

    assertEquals(false, v1.equals(v2));
    assertEquals(v2, service.version(owner));
    assertEquals("none", service.version(new AppUser()));
  }
}
