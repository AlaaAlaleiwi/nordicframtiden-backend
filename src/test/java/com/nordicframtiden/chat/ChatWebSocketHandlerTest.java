package com.nordicframtiden.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nordicframtiden.security.model.AppUser;
import com.nordicframtiden.security.repo.AppUserRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.PingMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ChatWebSocketHandlerTest {
  @Test
  void obsoleteCallSignalsAreRejectedButChatEventsStillReachMembers() throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    var members = mock(ChatRoomMemberRepository.class);
    var session = mock(WebSocketSession.class);
    when(session.getPrincipal()).thenReturn(() -> "alice");
    when(session.isOpen()).thenReturn(true);
    var sockets = new ChatWebSocketHandler(mapper, members, mock(AppUserRepository.class));
    sockets.afterConnectionEstablished(session);
    clearInvocations(session);

    sockets.handleMessage(session, new TextMessage("{\"type\":\"call.invite\",\"roomId\":1}"));
    var frame = ArgumentCaptor.forClass(WebSocketMessage.class);
    verify(session).sendMessage(frame.capture());
    assertThat(mapper.readTree((String) frame.getValue().getPayload()).path("code").asText()).isEqualTo("callsRemoved");
    verifyNoInteractions(members);

    clearInvocations(session);
    when(members.findUsernamesByRoomId(1L)).thenReturn(List.of("alice"));
    sockets.publish(1L, "message.created", Map.of("id", 42));
    verify(session).sendMessage(frame.capture());
    assertThat(mapper.readTree((String) frame.getValue().getPayload()).path("type").asText()).isEqualTo("message.created");
    assertThat(sockets.isOnline("alice")).isTrue();
    sockets.stopLivenessPinger();
  }

  private static WebSocketSession session(String username) {
    var session = mock(WebSocketSession.class);
    when(session.getPrincipal()).thenReturn(() -> username);
    when(session.isOpen()).thenReturn(true);
    return session;
  }

  private static AppUser account(String username, boolean enabled) {
    AppUser user = new AppUser();
    user.setUsername(username);
    user.setEnabled(enabled);
    return user;
  }

  @Test
  void livenessRoundClosesSocketsOfDisabledUsersAndKeepsOthers() throws Exception {
    var users = mock(AppUserRepository.class);
    AppUser alice = account("alice", true);
    when(users.findByUsername("alice")).thenReturn(Optional.of(alice));
    when(users.findByUsername("bob")).thenReturn(Optional.of(account("bob", true)));
    var sockets = new ChatWebSocketHandler(new ObjectMapper(), mock(ChatRoomMemberRepository.class), users);
    var aliceSession = session("alice");
    var bobSession = session("bob");
    sockets.afterConnectionEstablished(aliceSession);
    sockets.afterConnectionEstablished(bobSession);
    sockets.stopLivenessPinger();

    alice.setEnabled(false);
    clearInvocations(users);
    sockets.pingAllSessions();

    verify(aliceSession).close(CloseStatus.POLICY_VIOLATION);
    verify(aliceSession, never()).sendMessage(any(PingMessage.class));
    verify(bobSession).sendMessage(any(PingMessage.class));
    verify(bobSession, never()).close(any());
    assertThat(sockets.isOnline("alice")).isFalse();
    assertThat(sockets.isOnline("bob")).isTrue();
    // One lookup per distinct user per round.
    verify(users, times(1)).findByUsername("alice");
    verify(users, times(1)).findByUsername("bob");
  }

  @Test
  void livenessRoundClosesSocketsAfterLogoutEverywhereOrAccountRemoval() throws Exception {
    var users = mock(AppUserRepository.class);
    AppUser alice = account("alice", true);
    when(users.findByUsername("alice")).thenReturn(Optional.of(alice));
    when(users.findByUsername("bob")).thenReturn(Optional.of(account("bob", true)));
    var sockets = new ChatWebSocketHandler(new ObjectMapper(), mock(ChatRoomMemberRepository.class), users);
    var aliceSession = session("alice");
    var bobSession = session("bob");
    sockets.afterConnectionEstablished(aliceSession);
    sockets.afterConnectionEstablished(bobSession);
    sockets.stopLivenessPinger();

    alice.revokeTokens();
    when(users.findByUsername("bob")).thenReturn(Optional.empty());
    sockets.pingAllSessions();

    verify(aliceSession).close(CloseStatus.POLICY_VIOLATION);
    verify(bobSession).close(CloseStatus.POLICY_VIOLATION);
  }

  @Test
  void failedAccountLookupDoesNotDisconnectAnyone() throws Exception {
    var users = mock(AppUserRepository.class);
    when(users.findByUsername("alice")).thenReturn(Optional.of(account("alice", true)));
    var sockets = new ChatWebSocketHandler(new ObjectMapper(), mock(ChatRoomMemberRepository.class), users);
    var aliceSession = session("alice");
    sockets.afterConnectionEstablished(aliceSession);
    sockets.stopLivenessPinger();

    when(users.findByUsername("alice")).thenThrow(new IllegalStateException("db down"));
    sockets.pingAllSessions();

    verify(aliceSession, never()).close(any());
    verify(aliceSession).sendMessage(any(PingMessage.class));
  }

  @Test
  void closingTheOldSocketAfterAReconnectKeepsTheNewSessionRegistered() throws Exception {
    var members = mock(ChatRoomMemberRepository.class);
    when(members.findUsernamesByRoomId(1L)).thenReturn(List.of("alice"));
    var sockets = new ChatWebSocketHandler(new ObjectMapper(), members, mock(AppUserRepository.class));
    var oldSession = session("alice");
    var newSession = session("alice");
    sockets.afterConnectionEstablished(oldSession);
    sockets.afterConnectionEstablished(newSession);
    sockets.stopLivenessPinger();

    sockets.afterConnectionClosed(oldSession, CloseStatus.GOING_AWAY);
    clearInvocations(newSession);
    sockets.publish(1L, "message.created", Map.of("id", 1));

    assertThat(sockets.isOnline("alice")).isTrue();
    verify(newSession).sendMessage(any(TextMessage.class));
  }
}
