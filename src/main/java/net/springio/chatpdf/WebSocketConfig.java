package net.springio.chatpdf;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;

@Configuration
@EnableWebSocket
class WebSocketConfig implements WebSocketConfigurer {

    private static final Logger log = LoggerFactory.getLogger(WebSocketConfig.class);

    private final DocumentService documentService;

    WebSocketConfig(DocumentService documentService) {
        this.documentService = documentService;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(new TextWebSocketHandler() {
            @Override
            public void handleTextMessage(WebSocketSession session, TextMessage message) {
                documentService.streamQuestion(message.getPayload())
                        .doOnNext(token -> sendSafe(session, token))
                        .doOnComplete(() -> sendSafe(session, "[DONE]"))
                        .doOnError(e -> {
                            log.error("Streaming error", e);
                            sendSafe(session, "[ERROR]:" + e.getMessage());
                        })
                        .subscribe();
            }
        }, "/ws-chat").setAllowedOrigins("*");
    }

    private void sendSafe(WebSocketSession session, String text) {
        if (!session.isOpen()) return;
        synchronized (session) {
            try {
                session.sendMessage(new TextMessage(text));
            } catch (IOException e) {
                log.error("Failed to send WebSocket message", e);
            }
        }
    }
}
