package com.nordicframtiden.chat;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.UUID;

public interface CallHistoryRepository extends JpaRepository<CallHistory, UUID> {
  @Query("""
      select history from CallHistory history
      where exists (
        select member from ChatRoomMember member
        where member.room = history.room and member.user.username = :username
      )
      order by history.startedAt desc
      """)
  List<CallHistory> findForUser(@Param("username") String username, Pageable pageable);

  /** All calls the account placed — GDPR data export. */
  List<CallHistory> findByCaller(com.nordicframtiden.security.model.AppUser caller);

  /** Aggregate window queries for the admin dashboard KPIs. */
  List<CallHistory> findByStartedAtBetween(java.time.Instant from, java.time.Instant to);

  void deleteByCaller(com.nordicframtiden.security.model.AppUser caller);
}
