package com.nordicframtiden.chat;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Collection;
import java.util.Optional;

public interface ChatPushSubscriptionRepository extends JpaRepository<ChatPushSubscription, Long> {
  Optional<ChatPushSubscription> findByFirebaseInstallationId(String firebaseInstallationId);
  void deleteByFirebaseInstallationIdAndUserId(String firebaseInstallationId, Long userId);

  /** Used from FCM callbacks, which run outside any service transaction. */
  @Transactional
  void deleteByFirebaseInstallationId(String firebaseInstallationId);
  List<ChatPushSubscription> findByUserUsernameIn(Collection<String> usernames);

  @Query("""
      select subscription from ChatPushSubscription subscription
      join ChatRoomMember member on member.user = subscription.user
      where member.room.id = :roomId and subscription.user.id <> :senderId
      """)
  List<ChatPushSubscription> findForRoomExceptSender(@Param("roomId") Long roomId,
                                                     @Param("senderId") Long senderId);

  void deleteByUser(com.nordicframtiden.security.model.AppUser user);
}
