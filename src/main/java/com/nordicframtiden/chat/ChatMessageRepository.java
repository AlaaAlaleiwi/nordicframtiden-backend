package com.nordicframtiden.chat;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {
  interface RoomUnreadCount {
    Long getRoomId();
    long getUnreadCount();
  }

  @Query(value = """
      select m.room_id as roomId, count(*) as unreadCount
      from chat_message m
      join chat_room_member member on member.room_id = m.room_id and member.user_id = :userId
      where m.room_id in :roomIds and m.parent_message_id is null
        and m.id > coalesce(member.last_read_message_id, 0) and m.sender_id <> :userId
      group by m.room_id
      """, nativeQuery = true)
  List<RoomUnreadCount> countUnreadByRoomIds(@Param("roomIds") java.util.Collection<Long> roomIds,
                                           @Param("userId") Long userId);
  List<ChatMessage> findByRoomIdAndParentIsNullOrderByIdDesc(Long roomId, Pageable pageable);
  List<ChatMessage> findByRoomIdAndParentIsNullAndIdAfterOrderByIdAsc(Long roomId, Long afterId, Pageable pageable);
  List<ChatMessage> findByParentIdOrderByIdAsc(Long parentId);

  @Query("select count(m) from ChatMessage m where m.parent is null and m.room.id = :roomId and m.id > :afterId and m.sender.id <> :userId")
  long countUnread(@Param("roomId") Long roomId, @Param("afterId") Long afterId, @Param("userId") Long userId);

  long countByParentId(Long parentId);

  /**
   * Every message the account sent — any room (including rooms since left),
   * top-level and thread replies alike — for the GDPR data export.
   */
  @Query("""
      select m from ChatMessage m
      join fetch m.room
      where m.sender.id = :senderId
      order by m.id asc
      """)
  List<ChatMessage> findAllBySenderIdForExport(@Param("senderId") Long senderId);

  void deleteBySender(com.nordicframtiden.security.model.AppUser sender);
}
