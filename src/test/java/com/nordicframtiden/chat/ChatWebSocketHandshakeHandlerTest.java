package com.nordicframtiden.chat;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ChatWebSocketHandshakeHandlerTest {

  @Test
  void acknowledgesBearerProtocolWithoutEchoingToken() {
    ChatWebSocketHandshakeHandler handler = new ChatWebSocketHandshakeHandler();

    String selected = handler.selectProtocol(
        List.of("bearer", "header.payload.signature"), new ChatWebSocketHandler(null, null));

    assertThat(selected).isEqualTo("bearer");
    assertThat(selected).doesNotContain("header.payload.signature");
  }
}
