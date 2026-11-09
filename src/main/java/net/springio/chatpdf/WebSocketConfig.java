package net.springio.chatpdf;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import reactor.core.Disposable;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.UUID;

@Configuration
@EnableWebSocket
class WebSocketConfig implements WebSocketConfigurer {

    private static final Logger log = LoggerFactory.getLogger(WebSocketConfig.class);

    private final DocumentService documentService;
    private final ObjectMapper objectMapper;
    private final Map<String, Disposable> activeSubscriptions = new ConcurrentHashMap<>();

    WebSocketConfig(DocumentService documentService, ObjectMapper objectMapper) {
        this.documentService = documentService;
        this.objectMapper = objectMapper;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(new TextWebSocketHandler() {
            @Override
            public void handleTextMessage(WebSocketSession session, TextMessage message) {
                String payload = message.getPayload();

                // Stop generating if client requested cancellation
                if ("[STOP]".equalsIgnoreCase(payload.trim())) {
                    log.info("Received stop command from session: {}", session.getId());
                    stopSessionStream(session.getId());
                    sendSafe(session, "[DONE]");
                    return;
                }

                // If already streaming for this session, cancel previous
                stopSessionStream(session.getId());

                // Use the WebSocket session ID as the conversation ID for memory continuity
                ChatRequest request;

                try {
                    request = objectMapper.readValue(payload, ChatRequest.class);
                } catch (Exception e) {
                    sendSafe(session, "[ERROR]:Invalid chat request.");
                    return;
                }

                if (request.documentId() == null || request.conversationId() == null
                        || request.question() == null || request.question().isBlank()) {
                    sendSafe(session, "[ERROR]:Document ID and question are required.");
                    return;
                }
                try {
                    UUID.fromString(request.documentId());
                } catch (IllegalArgumentException e) {
                    sendSafe(session, "[ERROR]:Invalid document ID.");
                    return;
                }

                try {
                    UUID.fromString(request.conversationId());

                    if (!documentService.conversationBelongsToDocument(
                            request.conversationId(), request.documentId())) {
                        sendSafe(session, "[ERROR]:Invalid conversation for this document.");
                        return;
                    }

                    String conversationId = request.conversationId();

                    documentService.registerConversation(conversationId, request.documentId());
                    documentService.saveMessage(conversationId, "user", request.question());

                    StringBuilder assistantResponse = new StringBuilder();

                    Disposable subscription = documentService.streamQuestion(
                            request.question(),
                            conversationId,
                            request.documentId())
                            .doOnNext(token -> {
                                assistantResponse.append(token);
                                sendSafe(session, token);
                            })
                            .doOnComplete(() -> {
                                try {
                                    if (!assistantResponse.isEmpty()) {
                                        documentService.saveMessage(
                                                conversationId,
                                                "assistant",
                                                assistantResponse.toString());
                                    }
                                } finally {
                                    activeSubscriptions.remove(session.getId());
                                    sendSafe(session, "[DONE]");
                                }
                            })
                            .doOnError(e -> {
                                activeSubscriptions.remove(session.getId());
                                log.error("Streaming error for session {}: {}",
                                        session.getId(), e.getMessage());
                                sendSafe(session, "[ERROR]:Failed to generate response.");
                            })
                            .doOnCancel(() -> {
                                activeSubscriptions.remove(session.getId());
                                log.info("Stream cancelled for session: {}", session.getId());
                            })
                            .subscribe();

                    activeSubscriptions.put(session.getId(), subscription);

                } catch (IllegalArgumentException e) {
                    sendSafe(session, "[ERROR]:Invalid conversation or document ID.");
                } catch (RuntimeException e) {
                    log.error("Failed to process chat request", e);
                    sendSafe(session, "[ERROR]:Failed to process chat request.");
                }

            }

            @Override
            public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
                stopSessionStream(session.getId());
            }
        }, "/ws-chat").setAllowedOrigins("*");
    }

    private void stopSessionStream(String sessionId) {
        Disposable disposable = activeSubscriptions.remove(sessionId);
        if (disposable != null && !disposable.isDisposed()) {
            disposable.dispose();
            log.info("Disposed active stream for session: {}", sessionId);
        }
    }

    private void sendSafe(WebSocketSession session, String text) {
        if (!session.isOpen())
            return;
        synchronized (session) {
            try {
                session.sendMessage(new TextMessage(text));
            } catch (IOException e) {
                log.error("Failed to send WebSocket message", e);
            }
        }
    }

    private record ChatRequest(
            String question,
            String documentId,
            String conversationId) {
    }
}
