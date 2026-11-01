package net.springio.chatpdf;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.reader.pdf.config.PdfDocumentReaderConfig;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

@Service
class DocumentService {

    private static final Logger log = LoggerFactory.getLogger(DocumentService.class);

    private final ChatClient chatClient;
    private final VectorStore vectorStore;
    private final TokenTextSplitter tokenSplitter = new TokenTextSplitter();

    DocumentService(ChatClient chatClient, VectorStore vectorStore) {
        this.chatClient = chatClient;
        this.vectorStore = vectorStore;
    }

    void loadDocument(Resource resource) {
        try {
            var pdfReader = new PagePdfDocumentReader(resource, PdfDocumentReaderConfig.defaultConfig());
            var tokens = tokenSplitter.split(pdfReader.read());
            if (tokens.isEmpty()) {
                throw new IllegalStateException("No tokens were extracted from the PDF.");
            }
            vectorStore.write(tokens);
            log.info("Document uploaded to vector store. Token count: {}", tokens.size());
        } catch (Exception e) {
            log.error("Failed to load document", e);
            throw new RuntimeException("Failed to load document: " + e.getMessage(), e);
        }
    }

    String askQuestion(String question) {
        return chatClient.prompt().user(question).call().content();
    }

    Flux<String> streamQuestion(String question) {
        return chatClient.prompt().user(question).stream().content();
    }
}
