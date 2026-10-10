package com.nordicframtiden.documents;

import com.nordicframtiden.security.model.AppUser;
import com.nordicframtiden.security.repo.AppUserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Instant;

/**
 * The profile photo is a single encrypted {@link ProfileDocument} linked from
 * {@code app_user.photo_id} (not the profile table, so pure admins — who have
 * no user_profile row — can have a photo too). Uploading replaces any previous
 * photo document; clearing removes both link and document.
 * {@code photo_updated_at} acts as a cache-busting version for clients.
 */
@Service
public class ProfilePhotoService {

    public static final long MAX_PHOTO_BYTES = 5L * 1024 * 1024; // 5 MB

    private final ProfileDocumentRepository documents;
    private final DocumentEncryptionService encryption;
    private final AppUserRepository users;

    public ProfilePhotoService(ProfileDocumentRepository documents,
                               DocumentEncryptionService encryption,
                               AppUserRepository users) {
        this.documents = documents;
        this.encryption = encryption;
        this.users = users;
    }

    // ---------- DTOs ----------

    public record PhotoDto(Long documentId, String version) {}

    public record PhotoData(byte[] bytes, String contentType, String fileName, String version) {}

    // ---------- Operations ----------

    @Transactional
    public PhotoDto upload(Long userId, MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("File is empty");
        }
        if (file.getSize() > MAX_PHOTO_BYTES) {
            throw new IllegalArgumentException("Photo exceeds the 5 MB limit");
        }
        // The client-claimed type is ignored: the stored (and later served
        // inline) type comes from the file's own signature, and only raster
        // formats qualify — never SVG or anything else that can carry script.
        byte[] plaintext = file.getBytes();
        String contentType = detectRasterType(plaintext);
        if (contentType == null) {
            throw new IllegalArgumentException("Only JPEG, PNG or WebP images are allowed");
        }

        AppUser owner = users.findById(userId)
            .orElseThrow(() -> new IllegalArgumentException("User not found"));

        DocumentEncryptionService.Sealed sealed = encryption.encrypt(plaintext);

        ProfileDocument doc = new ProfileDocument();
        doc.setUser(owner);
        String name = file.getOriginalFilename();
        doc.setFileName(name == null || name.isBlank() ? "photo" : name);
        doc.setContentType(contentType);
        doc.setSizeBytes((long) plaintext.length);
        doc.setIv(sealed.iv());
        doc.setData(sealed.ciphertext());

        ProfileDocument saved = documents.save(doc);

        // Replace: drop the previous photo document after the new one is stored.
        Long previous = owner.getPhotoId();
        if (previous != null) {
            documents.deleteByIdAndUserId(previous, userId);
        }

        owner.setPhotoId(saved.getId());
        owner.setPhotoUpdatedAt(Instant.now());
        users.save(owner);

        return new PhotoDto(saved.getId(), version(owner));
    }

    @Transactional(readOnly = true)
    public PhotoData load(Long userId, String knownVersion) {
        AppUser owner = users.findById(userId)
            .orElseThrow(() -> new IllegalArgumentException("User not found"));

        if (owner.getPhotoId() == null) {
            throw new IllegalArgumentException("Photo not found");
        }

        ProfileDocument doc = documents.findById(owner.getPhotoId())
            .filter(d -> d.getUser() != null && userId.equals(d.getUser().getId()))
            .orElseThrow(() -> new IllegalArgumentException("Photo not found"));

        byte[] bytes = encryption.decrypt(doc.getIv(), doc.getData());
        // Photos stored before signature checks may carry any claimed type;
        // re-derive it so legacy SVG/HTML uploads are never served inline.
        String detected = detectRasterType(bytes);
        String contentType = detected == null ? "application/octet-stream" : detected;
        return new PhotoData(bytes, contentType, doc.getFileName(), version(owner));
    }

    /** Media type from the file signature for allowed raster formats; null otherwise. */
    static String detectRasterType(byte[] bytes) {
        if (bytes == null) return null;
        if (startsWith(bytes, 0, 0xFF, 0xD8, 0xFF)) return "image/jpeg";
        if (startsWith(bytes, 0, 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A)) return "image/png";
        if (startsWith(bytes, 0, 'R', 'I', 'F', 'F') && startsWith(bytes, 8, 'W', 'E', 'B', 'P')) {
            return "image/webp";
        }
        return null;
    }

    private static boolean startsWith(byte[] bytes, int offset, int... signature) {
        if (bytes.length < offset + signature.length) return false;
        for (int i = 0; i < signature.length; i++) {
            if ((bytes[offset + i] & 0xFF) != signature[i]) return false;
        }
        return true;
    }

    @Transactional
    public void clear(Long userId) {
        AppUser owner = users.findById(userId)
            .orElseThrow(() -> new IllegalArgumentException("User not found"));

        Long previous = owner.getPhotoId();
        owner.setPhotoId(null);
        owner.setPhotoUpdatedAt(null);
        users.save(owner);

        if (previous != null) {
            documents.deleteByIdAndUserId(previous, userId);
        }
    }

    /** Cache-busting version of the current photo; "none" when unset. */
    public String version(AppUser user) {
        if (user.getPhotoId() == null || user.getPhotoUpdatedAt() == null) {
            return "none";
        }
        return String.valueOf(user.getPhotoUpdatedAt().toEpochMilli());
    }
}
