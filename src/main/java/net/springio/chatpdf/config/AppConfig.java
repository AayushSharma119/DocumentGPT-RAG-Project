package net.springio.chatpdf.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.QuestionAnswerAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemory;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * AppConfig - Configures ChatClient based on the active profile.
 * If the openai profile is active, ChatClient for OpenAI is created.
 * If the ollama profile is active, ChatClient for Ollama is created.
 * <p>
 * Author: halilural
 */
@Configuration
public class AppConfig {

    private static final String SYSTEM_PROMPT = """
            You are DocumentGPT, an AI assistant that answers questions strictly based on the provided document context.
            The DOCUMENTS section below contains relevant excerpts from the uploaded PDF.
            Answer the user's question using only the information in those excerpts.
            If the document excerpts do not contain enough information, say clearly:
            "I couldn't find relevant information in the uploaded document for your question."
            Do not fabricate or assume information not present in the documents.
            Always be concise, accurate, and helpful.
            """;

    @Bean
    public ChatMemory chatMemory() {
        return new InMemoryChatMemory();
    }

    @Bean
    @Profile("openai")
    public ChatClient openAIChatClient(OpenAiChatModel chatModel, VectorStore vectorStore) {
        return ChatClient.builder(chatModel)
                .defaultSystem(SYSTEM_PROMPT)
                .defaultAdvisors(
                        new SimpleLoggerAdvisor(),
                        new QuestionAnswerAdvisor(vectorStore, SearchRequest.defaults().withTopK(8))
                )
                .build();
    }

    @Bean
    @Profile("ollama")
    public ChatClient ollamaChatClient(OllamaChatModel chatModel, VectorStore vectorStore) {
        return ChatClient.builder(chatModel)
                .defaultSystem(SYSTEM_PROMPT)
                .defaultAdvisors(
                        new SimpleLoggerAdvisor(),
                        new QuestionAnswerAdvisor(vectorStore, SearchRequest.defaults().withTopK(8))
                )
                .build();
    }
}
