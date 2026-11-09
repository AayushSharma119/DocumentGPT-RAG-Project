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

            var tokens = tokenSplitter.split(pdfReader.read());

            if (tokens.isEmpty()) {
                throw new IllegalStateException("No tokens were extracted from the PDF.");
            }

            tokens.forEach(token -> token.getMetadata().put("documentId", documentId));

            vectorStore.write(tokens);

            log.info("Document uploaded. ID: {}, chunks: {}", documentId, tokens.size());
            return documentId;

        } catch (Exception e) {
            log.error("Failed to load document", e);
            throw new RuntimeException("Failed to load document: " + e.getMessage(), e);
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
        UUID.fromString(documentId);

        int deletedChunks = jdbcTemplate.update(
                "DELETE FROM documentgpt_vectors WHERE metadata ->> 'documentId' = ?",
                documentId);

        Set<String> conversations = documentConversations.remove(documentId);

        if (conversations != null) {
            conversations.forEach(chatMemory::clear);
        }

        return deletedChunks;
    }
}
