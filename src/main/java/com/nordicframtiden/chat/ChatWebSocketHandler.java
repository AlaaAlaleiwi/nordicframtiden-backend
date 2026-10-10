package com.nordicframtiden.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nordicframtiden.security.model.AppUser;
import com.nordicframtiden.security.repo.AppUserRepository;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Component
public class ChatWebSocketHandler extends TextWebSocketHandler implements ChatEventPublisher, ChatPresence {
  private final ObjectMapper objectMapper;
  private final ChatRoomMemberRepository members;
  private final AppUserRepository users;
  private final Map<String, Set<WebSocketSession>> sessions = new ConcurrentHashMap<>();
  // Token version of the account when each socket connected; a later
  // "log out everywhere" bumps it and the next liveness round closes the socket.
  private final Map<WebSocketSession, Integer> tokenVersions = new ConcurrentHashMap<>();
  // Prunes silently-dead sockets (sleep/wake, crashes, network switches).
  // Without this, chat presence events are routed into dead
  // sessions and never reach the (reconnected) client.
  private final ScheduledExecutorService livenessPinger = Executors.newSingleThreadScheduledExecutor();

  public ChatWebSocketHandler(ObjectMapper objectMapper, ChatRoomMemberRepository members,
                              AppUserRepository users) {
    this.objectMapper = objectMapper;
    this.members = members;
    this.users = users;
  }

  @PostConstruct
  void startLivenessPinger() {
    livenessPinger.scheduleAtFixedRate(this::pingAllSessions, 30, 30, TimeUnit.SECONDS);
  }

  @PreDestroy
  void stopLivenessPinger() {
    livenessPinger.shutdownNow();
  }

  void pingAllSessions() {
    for (String username : List.copyOf(sessions.keySet())) {
      Set<WebSocketSession> userSessions = sessions.get(username);
      if (userSessions == null) continue;
      // One lookup per connected user per round. A failed lookup (e.g. the
      // database is briefly unreachable) skips the check rather than
      // disconnecting everyone.
      Optional<AppUser> account;
      try {
        account = users.findByUsername(username);
      } catch (RuntimeException lookupFailed) {
        account = null;
      }
      for (WebSocketSession session : Set.copyOf(userSessions)) {
        if (account != null && !stillAuthorized(account, session)) {
          closeSession(username, session, CloseStatus.POLICY_VIOLATION);
          continue;
        }
        try {
          synchronized (session) { session.sendMessage(new PingMessage()); }
        } catch (Exception ignored) {
          dropSession(username, session);
        }
      }
    }
  }

  /** The account still exists, is enabled and has not revoked its tokens since connecting. */
  private boolean stillAuthorized(Optional<AppUser> account, WebSocketSession session) {
    if (account.isEmpty() || !account.get().isEnabled()) return false;
    Integer connectedVersion = tokenVersions.get(session);
    return connectedVersion == null || connectedVersion == account.get().getTokenVersion();
  }

  @Override
  public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
    if (session.getPrincipal() != null) {
      dropSession(session.getPrincipal().getName(), session);
    }
  }

  @Override
  public void afterConnectionEstablished(WebSocketSession session) throws Exception {
    if (session.getPrincipal() == null) { session.close(CloseStatus.POLICY_VIOLATION); return; }
    String username = session.getPrincipal().getName();
    users.findByUsername(username)
        .ifPresent(user -> tokenVersions.put(session, user.getTokenVersion()));
    addSession(username, session);
  }

  @Override
  public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
    if (session.getPrincipal() == null) return;
    removeSession(session.getPrincipal().getName(), session);
  }

  /**
   * Adds one session. Shares the lock with {@link #removeSession} so a
   * reconnect racing the old socket's close can never have the close path
   * drop the user's freshly added set from the map.
   */
  private synchronized void addSession(String username, WebSocketSession session) {
    sessions.computeIfAbsent(username, ignored -> ConcurrentHashMap.newKeySet()).add(session);
    broadcastPresence(username, true);
  }

  /** Removes one session; runs the user-level disconnect path on the last one. */
  private synchronized void removeSession(String username, WebSocketSession session) {
    tokenVersions.remove(session);
    Set<WebSocketSession> userSessions = sessions.get(username);
    if (userSessions == null) return;
    userSessions.remove(session);
    if (!userSessions.isEmpty()) return;
    sessions.remove(username);
    broadcastPresence(username, false);
  }

  private void dropSession(String username, WebSocketSession session) {
    closeSession(username, session, CloseStatus.SESSION_NOT_RELIABLE);
  }

  private void closeSession(String username, WebSocketSession session, CloseStatus status) {
    removeSession(username, session);
    try { session.close(status); } catch (IOException ignored) { }
  }

  @Override
  protected void handleTextMessage(WebSocketSession session, TextMessage message) {
    if (session.getPrincipal() == null) return;
    try {
      if (objectMapper.readTree(message.getPayload()).path("type").asText().startsWith("call.")) {
        sendTo(Set.of(session.getPrincipal().getName()),
            Map.of("type", "error", "code", "callsRemoved", "message", "Audio and video calls are no longer available."));
      }
    } catch (IOException ignored) {
      // Chat writes use authenticated REST endpoints; unsupported socket
      // frames never trigger mutations or forwarding to other participants.
    }
  }

  @Override
  public void publish(Long roomId, String type, Object payload) {
    sendTo(members.findUsernamesByRoomId(roomId), Map.of("type", type, "roomId", roomId, "payload", payload));
  }

  @Override
  public void publishTo(Set<String> usernames, Long roomId, String type, Object payload) {
    sendTo(usernames, Map.of("type", type, "roomId", roomId, "payload", payload));
  }

  @Override
  public boolean isOnline(String username) {
    return sessions.containsKey(username);
  }

  private void broadcastPresence(String username, boolean online) {
    sendTo(sessions.keySet(), Map.of("type", "presence.changed", "username", username, "online", online));
  }

  private void sendTo(Iterable<String> usernames, Object event) {
    try {
      TextMessage message = new TextMessage(objectMapper.writeValueAsString(event));
      for (String username : usernames) {
        for (WebSocketSession session : sessions.getOrDefault(username, Set.of())) {
          if (!session.isOpen()) continue;
          try { synchronized (session) { session.sendMessage(message); } } catch (IOException ignored) { }
        }
      }
    } catch (IOException ignored) { }
  }
}
