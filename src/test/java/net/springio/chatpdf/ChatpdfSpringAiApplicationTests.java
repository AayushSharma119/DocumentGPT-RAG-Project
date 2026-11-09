
package net.springio.chatpdf;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.vectorstore.VectorStore;

@SpringBootTest
@TestPropertySource(properties = {
		"spring.ai.vectorstore.pgvector.initialize-schema=false"
})
class ChatpdfSpringAiApplicationTests {

	@MockBean
	private OllamaChatModel ollamaChatModel;

	@MockBean
	private VectorStore vectorStore;

	@Test
	void contextLoads() {
	}
}
