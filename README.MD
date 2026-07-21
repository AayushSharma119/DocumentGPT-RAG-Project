
---

## DocumentGPT Spring AI

**DocumentGPT** is a Spring AI-powered Retrieval-Augmented Generation (RAG) application that allows users to upload documents and query information using vector search. It supports PostgreSQL with pgvector for vector storage and can use **Ollama** or **OpenAI** for embeddings and model queries.

This project is based on the original repository: [salmar/documentgpt-spring-ai](https://github.com/salmar/documentgpt-spring-ai) with modifications and enhancements to support multiple AI providers and streamlined configurations.

---

### Prerequisites

1. **Docker and Docker Compose**  
   Ensure Docker is installed on your machine. [Download Docker](https://www.docker.com/get-started)

2. **Environment**
    - PostgreSQL with pgvector for vector storage.
    - Ollama or OpenAI for AI embedding and query models.

---

### Setup Instructions

#### **1. Clone the Repository**
```bash
git clone https://github.com/halilural/documentgpt-spring-ai.git
cd documentgpt-spring-ai
```

#### **2. Start the Services**
Start the PostgreSQL service (shared for both Ollama and OpenAI):
```bash
docker-compose -f compose-ollama.yml up -d
```

#### **3. Configure Spring Profiles**

- **Default Configuration (`application.properties`)**  
  This file contains shared settings like database configuration:
  ```properties
  spring.application.name=documentgpt-spring-ai

  # PostgreSQL pgvector configuration
  spring.datasource.url=jdbc:postgresql://localhost:5432/documentgpt
  spring.datasource.username=documentgpt
  spring.datasource.password=documentgptpassword
  spring.datasource.driver-class-name=org.postgresql.Driver
  spring.jpa.hibernate.ddl-auto=update

  # Spring AI Vector Store Configuration
  spring.ai.vectorstore.pgvector.initialize-schema=true
  spring.ai.vectorstore.pgvector.collection-name=documentgpt_vectors

  spring.servlet.multipart.max-file-size=10MB
  ```

- **Ollama-Specific Configuration (`application-ollama.properties`)**  
  Add Ollama-specific configurations here:
  ```properties
  spring.profiles.active=ollama

  spring.ai.ollama.chat.options.model=mistral
  spring.ai.ollama.embedding.options.model=nomic-embed-text
  ```

- **OpenAI-Specific Configuration (`application-openai.properties`)**  
  Add OpenAI-specific configurations here:
  ```properties
  spring.profiles.active=openai

  spring.ai.openai.api-key=YOUR_OPENAI_API_KEY
  spring.ai.openai.chat.options.model=gpt-4
  spring.ai.openai.embedding.options.model=text-embedding-ada-002
  ```

---

### Profile Activation

Activate a profile by specifying it in the `application.properties` file or using the `SPRING_PROFILES_ACTIVE` environment variable.

#### **Activate Ollama**:
Set the following in `application.properties`:
```properties
spring.profiles.active=ollama
```

Or use an environment variable:
```bash
SPRING_PROFILES_ACTIVE=ollama ./mvnw spring-boot:run
```

#### **Activate OpenAI**:
Set the following in `application.properties`:
```properties
spring.profiles.active=openai
```

Or use an environment variable:
```bash
SPRING_PROFILES_ACTIVE=openai ./mvnw spring-boot:run
```

---

### Pull Models for Ollama

If you're using **Ollama**, pull the required models after starting the container:
```bash
docker exec -it ollama ollama pull nomic-embed-text mistral
```

Verify the models:
```bash
docker exec -it ollama ollama list
```

---

### Start the Application

Start the application with Maven:
```bash
./mvnw spring-boot:run
```

---

### Usage

1. **Upload a Document**:
   Use the `/upload` endpoint to upload a PDF document. This will tokenize the content and store embeddings in the `documentgpt_vectors` table.

2. **Query Information**:
   Use the `/query` endpoint to ask questions based on the uploaded document.

---

### Troubleshooting

#### **Ollama-Specific Issues**
- **Error: Model Not Found**  
  Ensure you’ve pulled the required models:
  ```bash
  docker exec -it ollama ollama pull mistral
  ```

- **Verify Service**  
  Ensure the Ollama service is running:
  ```bash
  curl http://localhost:11434/models
  ```

#### **OpenAI-Specific Issues**
- **API Key Missing**  
  Ensure your `spring.ai.openai.api-key` is correctly set in `application-openai.properties`.

- **Rate Limits**  
  OpenAI models may have rate limits. Monitor usage in your OpenAI dashboard.

---

### Stopping the Services

To stop and remove the containers:
```bash
docker-compose -f compose-ollama.yml down
```

---

### Notes

- **Ollama**:
    - Requires pulling models manually using `ollama pull`.
    - Supports local inference without external API calls.

- **OpenAI**:
    - Requires an API key and internet connectivity.
    - Uses powerful cloud-based inference with OpenAI models.

---

This updated **README.md** includes clear instructions for handling multiple profiles and using the three properties files effectively. Let me know if further refinements are needed!