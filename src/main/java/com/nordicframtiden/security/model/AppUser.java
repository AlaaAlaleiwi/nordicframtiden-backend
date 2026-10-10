package com.nordicframtiden.security.model;

import jakarta.persistence.*;
import java.util.HashSet;
import java.util.Set;

@Entity
@Table(name = "app_user")
public class AppUser {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false, unique = true)
  private String username;

  @Column(nullable = false)
  private String passwordHash;

  @Column(nullable = false)
  private boolean enabled = true;

  @ElementCollection(fetch = FetchType.EAGER)
  @CollectionTable(name = "app_user_role", joinColumns = @JoinColumn(name = "user_id"))
  @Enumerated(EnumType.STRING)
  @Column(name = "role", nullable = false)
  private Set<Role> roles = new HashSet<>();@ElementCollection(fetch = FetchType.EAGER)
  @CollectionTable(name = "app_user_permissions", joinColumns = @JoinColumn(name = "user_id"))
  @Column(name = "permission")
  @Enumerated(EnumType.STRING)
  private Set<Permission> permissions = new HashSet<>();

  // Profile photo: reference to one encrypted profile_document row; nullable
  // because most accounts keep the initials fallback. photoUpdatedAt lets
  // clients cache the photo bytes and invalidate when the photo changes.
  // Lives on app_user so every role (admins included) can have a photo.
  @Column(name = "photo_id")
  private Long photoId;

  @Column(name = "photo_updated_at")
  private java.time.Instant photoUpdatedAt;

  // Embedded in every JWT ("tv"); a token is only accepted while it matches.
  // Bumped on password change, on disabling and on "log out everywhere", so
  // stolen or stale tokens stop working server-side.
  @Column(name = "token_version", nullable = false)
  private int tokenVersion;


  // getters/setters
  public Long getId() {
    return id;
  }

  public void setId(Long id) {
    this.id = id;
  }

  public String getUsername() {
    return username;
  }

  public void setUsername(String username) {
    this.username = username;
  }

  public String getPasswordHash() {
    return passwordHash;
  }

  public void setPasswordHash(String passwordHash) {
    // A changed password ends every existing session (field access: loading
    // from the database never runs this).
    if (this.passwordHash != null && !this.passwordHash.equals(passwordHash)) {
      revokeTokens();
    }
    this.passwordHash = passwordHash;
  }

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    // Disabling ends every session, so re-enabling never revives old tokens.
    if (this.enabled && !enabled) {
      revokeTokens();
    }
    this.enabled = enabled;
  }

  public int getTokenVersion() {
    return tokenVersion;
  }

  /** Invalidates every access and refresh token issued so far. */
  public void revokeTokens() {
    tokenVersion++;
  }

  public Set<Role> getRoles() {
    return roles;
  }

  public void setRoles(Set<Role> roles) {
    this.roles = roles;
  }

  public Set<Permission> getPermissions() {
    return permissions;
  }

  public void setPermissions(Set<Permission> permissions) {
    this.permissions = permissions;
  }

  public Long getPhotoId() { return photoId; }
  public void setPhotoId(Long photoId) { this.photoId = photoId; }

  public java.time.Instant getPhotoUpdatedAt() { return photoUpdatedAt; }
  public void setPhotoUpdatedAt(java.time.Instant photoUpdatedAt) { this.photoUpdatedAt = photoUpdatedAt; }
}
