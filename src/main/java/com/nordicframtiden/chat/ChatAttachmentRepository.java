package com.nordicframtiden.chat;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;

public interface ChatAttachmentRepository extends JpaRepository<ChatAttachment, Long> {

  List<ChatAttachment> findByMessageIdOrderById(Long messageId);

  List<ChatAttachment> findByIdInAndMessageIdIsNullAndUploaderId(List<Long> ids, Long uploaderId);

  void deleteByUploaderId(Long uploaderId);

  @Modifying
  void deleteByMessageId(Long messageId);
}
