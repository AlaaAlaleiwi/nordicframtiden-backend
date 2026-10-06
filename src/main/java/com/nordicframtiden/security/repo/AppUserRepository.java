package com.nordicframtiden.security.repo;

import com.nordicframtiden.security.model.AppUser;
import com.nordicframtiden.security.model.Role;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {
  interface ChatParticipantSummary {
    Long getId();
    String getUsername();
    String getDisplayName();
    Long getPhotoId();
  }

  @Query("""
      select u.id as id, u.username as username, u.photoId as photoId,
             coalesce(nullif(trim(p.fullName), ''), nullif(trim(a.fullName), ''), u.username) as displayName
      from AppUser u
      left join UserProfile p on p.user = u
      left join AdminProfile a on a.user = u
      where u.enabled = true and u.id <> :viewerId
      """)
  List<ChatParticipantSummary> findChatParticipants(@Param("viewerId") Long viewerId);

  @Query("""
      select u.id as id, u.username as username, u.photoId as photoId,
             coalesce(nullif(trim(p.fullName), ''), nullif(trim(a.fullName), ''), u.username) as displayName
      from AppUser u
      left join UserProfile p on p.user = u
      left join AdminProfile a on a.user = u
      where u.id in :userIds
      """)
  List<ChatParticipantSummary> findChatParticipantsByIdIn(@Param("userIds") java.util.Collection<Long> userIds);

  @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
  @Query("select u from AppUser u where u.id = :id")
  Optional<AppUser> lockForPayroll(@Param("id") Long id);

  Optional<AppUser> findByUsername(String username);

  boolean existsByUsername(String username);

  long countByEnabledTrue();

  @Query("select u from AppUser u join u.roles r where r = com.nordicframtiden.security.model.Role.ADMIN")
  List<AppUser> findAllAdmins();

  @Query("""
        select count(u)
        from AppUser u join u.roles r
        where r = :role
      """)
  long countByRole(@Param("role") Role role);

  @Query("""
        select distinct u
        from AppUser u
        join u.roles r
        where r = :role
      """)
  List<AppUser> findAllByRole(@Param("role") Role role);
}
