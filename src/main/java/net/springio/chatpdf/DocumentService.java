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
import org.springframework.ai.vectorstore.SearchRequest;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.ai.embedding.EmbeddingModel;

@Service
class DocumentService {

    private static final Logger log = LoggerFactory.getLogger(DocumentService.class);

    private final ChatClient chatClient;
    private final VectorStore vectorStore;
    private final EmbeddingModel embeddingModel;
    private final ChatMemory chatMemory;
    private final JdbcTemplate jdbcTemplate;
    private volatile boolean chatTablesInitialized = false;
    private final Map<String, Set<String>> documentConversations = new ConcurrentHashMap<>();
    private final TokenTextSplitter tokenSplitter = new TokenTextSplitter();

    DocumentService(ChatClient chatClient, VectorStore vectorStore,
            ChatMemory chatMemory, JdbcTemplate jdbcTemplate,
            EmbeddingModel embeddingModel) {
        this.chatClient = chatClient;
        this.vectorStore = vectorStore;
        this.chatMemory = chatMemory;
        this.jdbcTemplate = jdbcTemplate;
        this.embeddingModel = embeddingModel;
    }

    String loadDocument(Resource resource) {
        log.info("EMBEDDING MODEL: {}", embeddingModel.getClass().getName());
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
            log.info("Embedding model implementation: {}", embeddingModel.getClass().getName());
            log.info("Embedding model details: {}", embeddingModel);

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

    private List<org.springframework.ai.document.Document> retrieveDocuments(
            String question, String documentId) {

        String filter = "documentId == '" + UUID.fromString(documentId) + "'";

        long startTime = System.nanoTime();

        List<org.springframework.ai.document.Document> documents = vectorStore.similaritySearch(
                SearchRequest.defaults()
                        .withQuery(question)
                        .withTopK(8)
                        .withFilterExpression(filter));

        long retrievalLatency = (System.nanoTime() - startTime) / 1_000_000;

        log.info("RETRIEVAL LATENCY: {} ms | Retrieved chunks: {}",
                retrievalLatency, documents.size());

        return documents;
    }

    private String buildContext(
            List<org.springframework.ai.document.Document> documents) {

        if (documents == null || documents.isEmpty()) {
            return "No relevant document context was found.";
        }

        return documents.stream()
                .map(org.springframework.ai.document.Document::getContent)
                .collect(java.util.stream.Collectors.joining("\n\n"));
    }

    String askQuestion(String question, String conversationId, String documentId) {

        var documents = retrieveDocuments(question, documentId);
        String context = buildContext(documents);

        long generationStart = System.nanoTime();

        String response = chatClient.prompt()
                .advisors(new MessageChatMemoryAdvisor(chatMemory, conversationId, 20))
                .user("DOCUMENT CONTEXT:\n" + context
                        + "\n\nUSER QUESTION:\n" + question)
                .call()
                .content();

        long generationLatency = (System.nanoTime() - generationStart) / 1_000_000;

        log.info("GENERATION LATENCY: {} ms", generationLatency);

        return response;
    }

    Flux<String> streamQuestion(
            String question, String conversationId, String documentId) {

        return Flux.<String>defer(() -> {

            var documents = retrieveDocuments(question, documentId);
            String context = buildContext(documents);

            long generationStart = System.nanoTime();
            AtomicLong generatedTokens = new AtomicLong(0);

            return chatClient.prompt()
                    .advisors(new MessageChatMemoryAdvisor(chatMemory, conversationId, 20))
                    .user("DOCUMENT CONTEXT:\n" + context
                            + "\n\nUSER QUESTION:\n" + question)
                    .stream()
                    .chatResponse()
                    .doOnNext(response -> {
                        if (response.getMetadata() != null
                                && response.getMetadata().getUsage() != null
                                && response.getMetadata().getUsage().getGenerationTokens() != null) {

                            generatedTokens.set(
                                    response.getMetadata().getUsage().getGenerationTokens());
                        }
                    })
                    .<String>map(response -> {
                        if (response.getResult() == null) {
                            return "";
                        }

                        String content = response.getResult()
                                .getOutput()
                                .getContent();

                        return content != null ? content : "";
                    })
                    .filter(chunk -> !chunk.isEmpty())
                    .doFinally(signal -> {

                        long generationLatency = (System.nanoTime() - generationStart) / 1_000_000;

                        long tokens = generatedTokens.get();

                        double tokensPerSecond = generationLatency > 0
                                ? tokens / (generationLatency / 1000.0)
                                : 0;

                        log.info("GENERATION LATENCY: {} ms | Stream status: {}",
                                generationLatency, signal);

                        log.info("GENERATED TOKENS: {}", tokens);

                        log.info("GENERATION SPEED: {} tokens/sec",
                                String.format("%.2f", tokensPerSecond));
                    });
        });
    }

    int deleteDocument(String documentId, UUID userId) {
        ensureChatTables();

        UUID documentUuid = UUID.fromString(documentId);

        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM documents WHERE id = ? AND user_id = ?",
                Integer.class,
                documentUuid,
                userId);

        if (count == null || count == 0) {
            throw new IllegalArgumentException("Document not found.");
        }

        List<String> conversationIds = jdbcTemplate.queryForList(
                "SELECT id::text FROM chat_conversations WHERE document_id = ? AND user_id = ?",
                String.class,
                documentUuid,
                userId);

        conversationIds.forEach(chatMemory::clear);

        jdbcTemplate.update(
                "DELETE FROM chat_conversations WHERE document_id = ? AND user_id = ?",
                documentUuid,
                userId);

        int deletedChunks = jdbcTemplate.update(
                "DELETE FROM documentgpt_vectors WHERE metadata ->> 'documentId' = ?",
                documentId);

        jdbcTemplate.update(
                "DELETE FROM documents WHERE id = ? AND user_id = ?",
                documentUuid,
                userId);

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

    String createConversation(String documentId, String title, UUID userId) {
        ensureChatTables();

        UUID documentUuid = UUID.fromString(documentId);
        String conversationId = UUID.randomUUID().toString();

        int inserted = jdbcTemplate.update("""
                INSERT INTO chat_conversations (id, document_id, title, user_id)
                SELECT ?, id, ?, user_id
                FROM documents
                WHERE id = ? AND user_id = ?
                """,
                UUID.fromString(conversationId),
                title,
                documentUuid,
                userId);

        if (inserted != 1) {
            throw new IllegalArgumentException(
                    "Document not found or not owned by this user.");
        }

        return conversationId;
    }

    void saveMessage(String conversationId, String role, String content, UUID userId) {
        ensureChatTables();

        int inserted = jdbcTemplate.update("""
                INSERT INTO chat_messages (conversation_id, role, content)
                SELECT id, ?, ?
                FROM chat_conversations
                WHERE id = ? AND user_id = ?
                """,
                role,
                content,
                UUID.fromString(conversationId),
                userId);

        if (inserted != 1) {
            throw new IllegalArgumentException("Conversation not found or not owned by this user.");
        }

        jdbcTemplate.update(
                """
                        UPDATE chat_conversations
                        SET updated_at = CURRENT_TIMESTAMP
                        WHERE id = ? AND user_id = ?
                        """,
                UUID.fromString(conversationId),
                userId);
    }

    List<Map<String, Object>> getConversations(UUID userId) {
        ensureChatTables();

        return jdbcTemplate.queryForList("""
                SELECT id AS "conversationId",
                       document_id AS "documentId",
                       title,
                       created_at AS "createdAt"
                FROM chat_conversations
                WHERE user_id = ?
                ORDER BY updated_at DESC
                """, userId);
    }

    List<Map<String, Object>> getMessages(String conversationId, UUID userId) {
        ensureChatTables();

        return jdbcTemplate.queryForList("""
                SELECT m.role, m.content, m.created_at AS "createdAt"
                FROM chat_messages m
                JOIN chat_conversations c ON m.conversation_id = c.id
                WHERE c.id = ? AND c.user_id = ?
                ORDER BY m.id ASC
                """,
                UUID.fromString(conversationId),
                userId);
    }

    boolean conversationBelongsToDocument(
            String conversationId, String documentId, UUID userId) {

        ensureChatTables();

        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM chat_conversations c
                JOIN documents d ON c.document_id = d.id
                WHERE c.id = ?
                  AND c.document_id = ?
                  AND c.user_id = ?
                  AND d.user_id = ?
                """,
                Integer.class,
                UUID.fromString(conversationId),
                UUID.fromString(documentId),
                userId,
                userId);

        return count != null && count > 0;
    }

    void deleteConversation(String conversationId, UUID userId) {
        ensureChatTables();

        UUID id = UUID.fromString(conversationId);

        int deleted = jdbcTemplate.update(
                "DELETE FROM chat_conversations WHERE id = ? AND user_id = ?",
                id,
                userId);

        if (deleted == 0) {
            throw new IllegalArgumentException("Conversation not found.");
        }

        chatMemory.clear(conversationId);
    }

    void restoreConversationMemory(String conversationId, UUID userId) {
        {
            if (!chatMemory.get(conversationId, 1).isEmpty()) {
                return;
            }

            List<Map<String, Object>> messages = getMessages(conversationId, userId);

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
}
