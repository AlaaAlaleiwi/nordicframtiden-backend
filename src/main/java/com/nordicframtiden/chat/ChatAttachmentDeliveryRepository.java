package com.nordicframtiden.chat;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

public interface ChatAttachmentDeliveryRepository extends JpaRepository<ChatAttachmentDelivery, ChatAttachmentDelivery.Id> {

  long countByAttachmentId(Long attachmentId);

  boolean existsByAttachmentIdAndUserId(Long attachmentId, Long userId);

  @Modifying
  void deleteByAttachmentId(Long attachmentId);

  /**
   * Records a delivery idempotently: concurrent first downloads by the same
   * member race on the primary key, and the loser must not turn into a 500.
   */
  @Modifying
  @Transactional
  @Query(value = """
      insert into chat_attachment_delivery (attachment_id, user_id, delivered_at)
      values (:attachmentId, :userId, :deliveredAt)
      on conflict (attachment_id, user_id) do nothing
      """, nativeQuery = true)
  int recordDelivery(@Param("attachmentId") Long attachmentId, @Param("userId") Long userId,
                     @Param("deliveredAt") Instant deliveredAt);

  /**
   * Distinct attachment ids that carry a message and were delivered to everyone.
   * Only deliveries by users who are still members count: rows of departed or
   * deleted accounts must not stand in for a remaining member's download.
   */
  @Query("""
      select a.id from ChatAttachment a
      where a.messageId is not null
        and a.purgedAt is null
        and (select count(d) from ChatAttachmentDelivery d
              where d.attachmentId = a.id
                and exists (select dm.user.id from ChatRoomMember dm
                             where dm.user.id = d.userId
                               and dm.room.id = (select dmsg.room.id from ChatMessage dmsg
                                                  where dmsg.id = a.messageId)))
            >= (select count(m.user.id) from ChatRoomMember m where m.room.id =
                  (select msg.room.id from ChatMessage msg where msg.id = a.messageId))
      """)
  List<Long> findFullyDeliveredAttachmentIds();

  /** Orphaned uploads: never bound to a message and older than the cutoff. */
  @Query("""
      select a.id from ChatAttachment a
      where a.messageId is null
        and a.purgedAt is null
        and a.createdAt < :cutoff
      """)
  List<Long> findOrphanedAttachmentIds(@Param("cutoff") Instant cutoff);

  /** Attachments still undelivered to everyone after the retention window. */
  @Query("""
      select a.id from ChatAttachment a
      where a.messageId is not null
        and a.purgedAt is null
        and a.createdAt < :cutoff
      """)
  List<Long> findExpiredUndeliveredAttachmentIds(@Param("cutoff") Instant cutoff);
}
