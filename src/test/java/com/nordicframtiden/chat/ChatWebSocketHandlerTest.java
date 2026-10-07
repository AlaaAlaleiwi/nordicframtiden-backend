package com.nordicframtiden.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ChatWebSocketHandlerTest {
  @Test
  void obsoleteCallSignalsAreRejectedButChatEventsStillReachMembers() throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    var members = mock(ChatRoomMemberRepository.class);
    var session = mock(WebSocketSession.class);
    when(session.getPrincipal()).thenReturn(() -> "alice");
    when(session.isOpen()).thenReturn(true);
    var sockets = new ChatWebSocketHandler(mapper, members);
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
}
