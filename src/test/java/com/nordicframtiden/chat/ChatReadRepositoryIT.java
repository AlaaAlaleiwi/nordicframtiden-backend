package com.nordicframtiden.chat;

import com.nordicframtiden.security.repo.AppUserRepository;
import jakarta.persistence.EntityManager;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(properties = {"spring.jpa.hibernate.ddl-auto=validate", "spring.jpa.properties.hibernate.generate_statistics=true"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class ChatReadRepositoryIT {
  @Autowired AppUserRepository users;
  @Autowired ChatMessageRepository messages;
  @Autowired ChatRoomMemberRepository members;
  @Autowired JdbcTemplate jdbc;
  @Autowired EntityManager entityManager;

  private Long user(String name, boolean enabled) {
    return jdbc.queryForObject("insert into app_user(username,password_hash,enabled) values (?, 'unused', ?) returning id",
        Long.class, name + System.nanoTime(), enabled);
  }

  private void profile(Long user, String name, boolean admin) {
    if (admin) {
      jdbc.update("insert into admin_profile(user_id,full_name,email,phone) values (?,?,?,?)",
          user, name, user + "@admin.test", "admin-" + user);
    } else {
      jdbc.update("insert into user_profile(user_id,full_name,email,phone,year_of_birth,county_code,municipality_code) values (?,?,?,?,1990,'01','0114')",
          user, name, user + "@user.test", "user-" + user);
    }
  }

  @Test
  void participantProjectionPreservesNameFallbackAndFiltersWithoutLoadingUserEntities() {
    Long viewer = user("viewer", true);
    Long dual = user("dual", true);
    Long admin = user("admin", true);
    Long blank = user("blank", true);
    Long fallback = user("fallback", true);
    Long disabled = user("disabled", false);
    profile(dual, "User name", false);
    profile(dual, "Admin name", true);
    profile(admin, "Admin only", true);
    profile(blank, "   ", false);
    profile(blank, "Blank fallback", true);
    Long photo = jdbc.queryForObject("""
        insert into profile_document(user_id,file_name,content_type,size_bytes,iv,data)
        values (?,'photo.png','image/png',1,decode('00','hex'),decode('00','hex')) returning id
        """, Long.class, dual);
    jdbc.update("update app_user set photo_id=? where id=?", photo, dual);
    var stats = entityManager.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
    stats.clear();

    var rows = users.findChatParticipants(viewer);
    assertThat(stats.getPrepareStatementCount()).isEqualTo(1);
    assertThat(stats.getEntityLoadCount()).isZero();
    assertThat(rows).extracting(AppUserRepository.ChatParticipantSummary::getId).contains(dual, admin, blank, fallback)
        .doesNotContain(viewer, disabled);
    assertThat(rows.stream().filter(row -> row.getId().equals(dual)).findFirst().orElseThrow().getDisplayName())
        .isEqualTo("User name");
    assertThat(rows.stream().filter(row -> row.getId().equals(dual)).findFirst().orElseThrow().getPhotoId())
        .isEqualTo(photo);
    assertThat(rows.stream().filter(row -> row.getId().equals(admin)).findFirst().orElseThrow().getDisplayName())
        .isEqualTo("Admin only");
    assertThat(rows.stream().filter(row -> row.getId().equals(blank)).findFirst().orElseThrow().getDisplayName())
        .isEqualTo("Blank fallback");
    var bare = rows.stream().filter(row -> row.getId().equals(fallback)).findFirst().orElseThrow();
    assertThat(bare.getDisplayName()).isEqualTo(bare.getUsername());
    // Room membership may retain disabled accounts, including the viewer.
    assertThat(users.findChatParticipantsByIdIn(List.of(viewer, disabled)))
        .extracting(AppUserRepository.ChatParticipantSummary::getId).containsExactlyInAnyOrder(viewer, disabled);
  }

  private Long room(Long owner) {
    return jdbc.queryForObject("insert into chat_room(type,name,created_by) values ('CHANNEL',?,?) returning id",
        Long.class, "room-" + System.nanoTime(), owner);
  }

  private Long message(Long room, Long sender, Long parent) {
    return jdbc.queryForObject("insert into chat_message(room_id,sender_id,parent_message_id,body) values (?,?,?,'message') returning id",
        Long.class, room, sender, parent);
  }

  @Test
  void groupedUnreadCountsRespectMembershipReadCursorSenderAndThreads() {
    Long viewer = user("reader", true);
    Long other = user("sender", true);
    Long joined = room(other);
    Long unreadRoom = room(other);
    Long discoverable = room(other);
    Long read = message(joined, other, null);
    jdbc.update("insert into chat_room_member(room_id,user_id,last_read_message_id) values (?,?,?)", joined, viewer, read);
    jdbc.update("insert into chat_room_member(room_id,user_id) values (?,?)", unreadRoom, viewer);
    message(joined, viewer, null);
    message(joined, other, read);
    message(joined, other, null);
    message(unreadRoom, other, null);
    message(unreadRoom, other, null);
    message(discoverable, other, null);

    var counts = messages.countUnreadByRoomIds(List.of(joined, unreadRoom, discoverable), viewer).stream()
        .collect(java.util.stream.Collectors.toMap(ChatMessageRepository.RoomUnreadCount::getRoomId,
            ChatMessageRepository.RoomUnreadCount::getUnreadCount));
    assertThat(counts).containsEntry(joined, 1L).containsEntry(unreadRoom, 2L).doesNotContainKey(discoverable);
    assertThat(members.findByRoomIdIn(List.of(joined, unreadRoom)))
        .hasSize(2);
  }

  @Test
  void migrationCreatesIndexesForTheAuditedReadPaths() {
    assertThat(jdbc.queryForList("select indexname from pg_indexes where schemaname='public'", String.class))
        .contains("idx_schedule_shift_user_range", "idx_schedule_shift_pharmacy_range", "idx_staff_shift_user_range",
            "ix_call_history_started", "ix_chat_message_root_room");
  }
}
