package net.springio.chatpdf;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.reader.pdf.config.PdfDocumentReaderConfig;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import org.springframework.ai.chat.client.advisor.QuestionAnswerAdvisor;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Service
class DocumentService {

    private static final Logger log = LoggerFactory.getLogger(DocumentService.class);

    private final ChatClient chatClient;
    private final VectorStore vectorStore;
    private final ChatMemory chatMemory;
    private final JdbcTemplate jdbcTemplate;
    private volatile boolean chatTablesInitialized = false;
    private final Map<String, Set<String>> documentConversations = new ConcurrentHashMap<>();
    private final TokenTextSplitter tokenSplitter = new TokenTextSplitter();

    DocumentService(ChatClient chatClient, VectorStore vectorStore,
            ChatMemory chatMemory, JdbcTemplate jdbcTemplate) {
        this.chatClient = chatClient;
        this.vectorStore = vectorStore;
        this.chatMemory = chatMemory;
        this.jdbcTemplate = jdbcTemplate;
    }

    String loadDocument(Resource resource) {
        try {
            String documentId = UUID.randomUUID().toString();

            var pdfReader = new PagePdfDocumentReader(
                    resource, PdfDocumentReaderConfig.defaultConfig());

            var pages = pdfReader.read();

            if (pages.isEmpty()) {
                throw new IllegalArgumentException("PDF contains no readable pages.");
            }

            var tokens = tokenSplitter.split(pages);

            if (tokens.isEmpty()) {
                throw new IllegalArgumentException("PDF contains no extractable text.");
            }

            tokens.forEach(token -> token.getMetadata().put("documentId", documentId));

            vectorStore.write(tokens);

            log.info("Document uploaded. ID: {}, chunks: {}", documentId, tokens.size());
            return documentId;

        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to load document", e);
            throw new RuntimeException("Failed to process document.", e);
        }
    }

    String getConversationId(String sessionId, String documentId) {
        UUID.fromString(documentId);

        String conversationId = sessionId + ":" + documentId;

        documentConversations
                .computeIfAbsent(documentId, id -> ConcurrentHashMap.newKeySet())
                .add(conversationId);

        return conversationId;
    }

    void registerConversation(String conversationId, String documentId) {
        UUID.fromString(conversationId);
        UUID.fromString(documentId);

        documentConversations
                .computeIfAbsent(documentId, id -> ConcurrentHashMap.newKeySet())
                .add(conversationId);
    }

    String askQuestion(String question, String conversationId, String documentId) {
        String filter = "documentId == '" + UUID.fromString(documentId) + "'";

        return chatClient.prompt()
                .advisors(new MessageChatMemoryAdvisor(chatMemory, conversationId, 20))
                .advisors(a -> a.param(QuestionAnswerAdvisor.FILTER_EXPRESSION, filter))
                .user(question)
                .call()
                .content();
    }

    Flux<String> streamQuestion(String question, String conversationId, String documentId) {
        String filter = "documentId == '" + UUID.fromString(documentId) + "'";

        return chatClient.prompt()
                .advisors(new MessageChatMemoryAdvisor(chatMemory, conversationId, 20))
                .advisors(a -> a.param(QuestionAnswerAdvisor.FILTER_EXPRESSION, filter))
                .user(question)
                .stream()
                .content();
    }

    int deleteDocument(String documentId) {
        ensureChatTables();
        UUID documentUuid = UUID.fromString(documentId);

        List<String> conversationIds = jdbcTemplate.queryForList(
                "SELECT id::text FROM chat_conversations WHERE document_id = ?",
                String.class,
                documentUuid);

        conversationIds.forEach(chatMemory::clear);

        jdbcTemplate.update(
                "DELETE FROM chat_conversations WHERE document_id = ?",
                documentUuid);

        int deletedChunks = jdbcTemplate.update(
                "DELETE FROM documentgpt_vectors WHERE metadata ->> 'documentId' = ?",
                documentId);

        Set<String> conversations = documentConversations.remove(documentId);

        if (conversations != null) {
            conversations.forEach(chatMemory::clear);
        }

        return deletedChunks;
    }

    void initializeChatTables() {
        ensureChatTables();
    }

    public UUID getUserId(String username) {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM app_users WHERE username = ?",
                UUID.class,
                username);
    }

    public void saveDocument(String documentId, UUID userId, String filename) {
        jdbcTemplate.update(
                "INSERT INTO documents (id, user_id, filename) VALUES (?, ?, ?)",
                UUID.fromString(documentId),
                userId,
                filename);
    }

    private synchronized void ensureChatTables() {
        if (chatTablesInitialized) {
            return;
        }
        jdbcTemplate.execute("""
                    CREATE TABLE IF NOT EXISTS app_users (
                        id UUID PRIMARY KEY,
                        username VARCHAR(100) UNIQUE NOT NULL,
                        email VARCHAR(255) UNIQUE NOT NULL,
                        password VARCHAR(255) NOT NULL,
                        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                    )
                """);
        jdbcTemplate.execute("""
                    CREATE TABLE IF NOT EXISTS documents (
                        id UUID PRIMARY KEY,
                        user_id UUID NOT NULL REFERENCES app_users(id),
                        filename VARCHAR(255) NOT NULL,
                        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                    )
                """);
        jdbcTemplate.execute("""
                    CREATE TABLE IF NOT EXISTS chat_conversations (
                        id UUID PRIMARY KEY,
                        document_id UUID NOT NULL,
                        title VARCHAR(255) NOT NULL,
                        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                        updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                    )
                """);
        jdbcTemplate.execute("""
                    ALTER TABLE chat_conversations
                    ADD COLUMN IF NOT EXISTS user_id UUID REFERENCES app_users(id)
                """);
        jdbcTemplate.execute("""
                    CREATE TABLE IF NOT EXISTS chat_messages (
                        id BIGSERIAL PRIMARY KEY,
                        conversation_id UUID NOT NULL REFERENCES chat_conversations(id)
                            ON DELETE CASCADE,
                        role VARCHAR(20) NOT NULL,
                        content TEXT NOT NULL,
                        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                    )
                """);

        chatTablesInitialized = true;
    }

    String createConversation(String documentId, String title) {
        ensureChatTables();

        UUID documentUuid = UUID.fromString(documentId);
        String conversationId = UUID.randomUUID().toString();

        jdbcTemplate.update(
                "INSERT INTO chat_conversations (id, document_id, title) VALUES (?, ?, ?)",
                UUID.fromString(conversationId),
                documentUuid,
                title);

        return conversationId;
    }

    void saveMessage(String conversationId, String role, String content) {
        ensureChatTables();

        jdbcTemplate.update(
                "INSERT INTO chat_messages (conversation_id, role, content) VALUES (?, ?, ?)",
                UUID.fromString(conversationId),
                role,
                content);

        jdbcTemplate.update(
                "UPDATE chat_conversations SET updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                UUID.fromString(conversationId));
    }

    List<Map<String, Object>> getConversations() {
        ensureChatTables();

        return jdbcTemplate.queryForList("""
                    SELECT id AS "conversationId",
                           document_id AS "documentId",
                           title,
                           created_at AS "createdAt"
                    FROM chat_conversations
                    ORDER BY updated_at DESC
                """);
    }

    List<Map<String, Object>> getMessages(String conversationId) {
        ensureChatTables();

        return jdbcTemplate.queryForList("""
                    SELECT role, content, created_at AS "createdAt"
                    FROM chat_messages
                    WHERE conversation_id = ?
                    ORDER BY id ASC
                """, UUID.fromString(conversationId));
    }

    boolean conversationBelongsToDocument(
            String conversationId, String documentId) {
        ensureChatTables();

        Integer count = jdbcTemplate.queryForObject("""
                    SELECT COUNT(*) FROM chat_conversations
                    WHERE id = ? AND document_id = ?
                """, Integer.class,
                UUID.fromString(conversationId),
                UUID.fromString(documentId));

        return count != null && count > 0;
    }

    void deleteConversation(String conversationId) {
        ensureChatTables();

        UUID id = UUID.fromString(conversationId);

        jdbcTemplate.update(
                "DELETE FROM chat_conversations WHERE id = ?", id);

        chatMemory.clear(conversationId);
    }

    void restoreConversationMemory(String conversationId) {
        if (!chatMemory.get(conversationId, 1).isEmpty()) {
            return;
        }

        List<Map<String, Object>> messages = getMessages(conversationId);

        List<org.springframework.ai.chat.messages.Message> chatMessages = new java.util.ArrayList<>();

        for (Map<String, Object> message : messages) {
            String role = message.get("role").toString();
            String content = message.get("content").toString();

            if ("user".equals(role)) {
                chatMessages.add(
                        new org.springframework.ai.chat.messages.UserMessage(content));
            } else if ("assistant".equals(role)) {
                chatMessages.add(
                        new org.springframework.ai.chat.messages.AssistantMessage(content));
            }
        }

        if (!chatMessages.isEmpty()) {
            chatMemory.add(conversationId, chatMessages);
        }
    }

}
