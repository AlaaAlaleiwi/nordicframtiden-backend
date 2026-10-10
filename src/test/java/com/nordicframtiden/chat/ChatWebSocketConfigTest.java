package com.nordicframtiden.chat;

import org.junit.jupiter.api.Test;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistration;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatWebSocketConfigTest {

    @Test
    void allowsProductionCustomDomainsForWebSocketConnections() {
        ChatWebSocketHandler handler = mock(ChatWebSocketHandler.class);
        ChatWebSocketHandshakeHandler handshakeHandler = mock(ChatWebSocketHandshakeHandler.class);
        WebSocketHandlerRegistry registry = mock(WebSocketHandlerRegistry.class);
        WebSocketHandlerRegistration registration = mock(WebSocketHandlerRegistration.class);
        when(registry.addHandler(handler, "/ws/chat")).thenReturn(registration);
        when(registration.setHandshakeHandler(handshakeHandler)).thenReturn(registration);

        new ChatWebSocketConfig(handler, handshakeHandler).registerWebSocketHandlers(registry);

        verify(registration).setAllowedOrigins(
                "http://localhost:5173",
                "http://localhost:3000",
                "https://nordicframtiden-frontend-34c6b049a0f5.herokuapp.com",
                "https://nordicframtiden-frontend-644311628279.europe-north1.run.app",
                "https://nordicframtiden-frontend-mbtjtlqpcq-lz.a.run.app",
                "https://nordicframtiden.se",
                "https://www.nordicframtiden.se");
    }

    @Test
    void capsInboundFramesAtChatEventSizeNowThatCallSignallingIsGone() {
        var container = new ChatWebSocketConfig(mock(ChatWebSocketHandler.class),
                mock(ChatWebSocketHandshakeHandler.class)).createWebSocketContainer();

        assertThat(ChatWebSocketConfig.MAX_MESSAGE_BUFFER_BYTES).isEqualTo(64 * 1024);
        assertThat(org.springframework.test.util.ReflectionTestUtils.getField(container, "maxTextMessageBufferSize"))
                .isEqualTo(64 * 1024);
        assertThat(org.springframework.test.util.ReflectionTestUtils.getField(container, "maxBinaryMessageBufferSize"))
                .isEqualTo(64 * 1024);
    }
}
