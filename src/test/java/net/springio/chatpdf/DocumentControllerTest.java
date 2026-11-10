
package net.springio.chatpdf;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;
import java.security.Principal;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DocumentControllerTest {

        private final DocumentService documentService = mock(DocumentService.class);
        private final DocumentController controller = new DocumentController(documentService);
        private final Principal principal = () -> "testuser";

        @Test
        void shouldRejectEmptyFile() {
                MockMultipartFile file = new MockMultipartFile(
                                "fileToUpload", "test.pdf", "application/pdf", new byte[0]);

                ResponseEntity<?> response = controller.uploadDocument(file, principal);

                assertEquals(400, response.getStatusCode().value());
                verifyNoInteractions(documentService);
        }

        @Test
        void shouldRejectNonPdfFile() {
                MockMultipartFile file = new MockMultipartFile(
                                "fileToUpload", "test.txt", "text/plain", "hello".getBytes());

                ResponseEntity<?> response = controller.uploadDocument(file, principal);

                assertEquals(400, response.getStatusCode().value());
                verifyNoInteractions(documentService);
        }

        @Test
        void shouldRejectInvalidPdfSignature() {
                MockMultipartFile file = new MockMultipartFile(
                                "fileToUpload", "test.pdf", "application/pdf",
                                "hello world".getBytes());

                ResponseEntity<?> response = controller.uploadDocument(file, principal);

                assertEquals(400, response.getStatusCode().value());
                verifyNoInteractions(documentService);
        }

        @Test
        void shouldUploadValidPdf() {
                byte[] pdfContent = "%PDF-1.4\nsample".getBytes();

                MockMultipartFile file = new MockMultipartFile(
                                "fileToUpload", "test.pdf", "application/pdf", pdfContent);

                when(documentService.loadDocument(any()))
                                .thenReturn("123e4567-e89b-12d3-a456-426614174000");
                when(documentService.createConversation(
                                "123e4567-e89b-12d3-a456-426614174000",
                                "test.pdf"))
                                .thenReturn("223e4567-e89b-12d3-a456-426614174000");
                UUID userId = UUID.randomUUID();

                when(documentService.getUserId("testuser"))
                                .thenReturn(userId);
                ResponseEntity<?> response = controller.uploadDocument(file, principal);

                assertEquals(200, response.getStatusCode().value());
                verify(documentService).loadDocument(any());
                verify(documentService).createConversation(
                                "123e4567-e89b-12d3-a456-426614174000",
                                "test.pdf");
                verify(documentService).saveDocument(
                                "123e4567-e89b-12d3-a456-426614174000",
                                userId,
                                "test.pdf");
        }

        @Test
        void shouldRejectInvalidDocumentIdDuringDeletion() {
                when(documentService.deleteDocument("invalid-id"))
                                .thenThrow(new IllegalArgumentException());

                ResponseEntity<?> response = controller.deleteDocument("invalid-id");

                assertEquals(400, response.getStatusCode().value());
        }
}
