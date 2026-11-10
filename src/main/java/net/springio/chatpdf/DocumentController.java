package net.springio.chatpdf;

import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.security.Principal;
import java.util.Map;
import java.util.Objects;

@Controller
public class DocumentController {

    private final DocumentService documentService;

    public DocumentController(DocumentService documentService) {
        this.documentService = documentService;
    }

    @GetMapping("/")
    public String index() {
        return "index";
    }

    @PostMapping("/upload")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> uploadDocument(
            @RequestParam("fileToUpload") MultipartFile document, Principal principal) {

        if (document.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("success", false, "error", "Uploaded file is empty."));
        }

        if (document.getSize() > 10 * 1024 * 1024) {
            return ResponseEntity.badRequest()
                    .body(Map.of("success", false, "error", "File exceeds 10 MB limit."));
        }

        String filename = document.getOriginalFilename();

        if (filename == null || !filename.toLowerCase().endsWith(".pdf")) {
            return ResponseEntity.badRequest()
                    .body(Map.of("success", false, "error", "Only PDF files are accepted."));
        }

        try (var inputStream = document.getInputStream()) {
            byte[] header = inputStream.readNBytes(5);

            if (!new String(header, java.nio.charset.StandardCharsets.US_ASCII).equals("%PDF-")) {
                return ResponseEntity.badRequest()
                        .body(Map.of("success", false, "error", "Invalid PDF file."));
            }
        } catch (java.io.IOException e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("success", false, "error", "Unable to read uploaded file."));
        }

        try {
            String documentId = documentService.loadDocument(document.getResource());
            documentService.saveDocument(
                    documentId,
                    documentService.getUserId(principal.getName()),
                    filename);
            String conversationId = documentService.createConversation(
                    documentId,
                    Objects.requireNonNullElse(document.getOriginalFilename(), "document.pdf"));
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "documentId", documentId,
                    "conversationId", conversationId,
                    "filename", Objects.requireNonNullElse(document.getOriginalFilename(), "document.pdf"),
                    "size", document.getSize()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("success", false, "error", e.getMessage()));
        } catch (RuntimeException e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("success", false, "error", "Failed to process document."));
        }
    }

    @GetMapping("/chat")
    public String loadChat() {
        return "chat";
    }

    @DeleteMapping("/documents/{documentId}")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> deleteDocument(
            @PathVariable String documentId) {

        try {
            int deletedChunks = documentService.deleteDocument(documentId);

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "deletedChunks", deletedChunks));

        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("success", false, "error", "Invalid document ID."));

        } catch (RuntimeException e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("success", false, "error", "Failed to delete document."));
        }
    }

    @GetMapping("/conversations")
    @ResponseBody
    public ResponseEntity<?> getConversations() {
        return ResponseEntity.ok(documentService.getConversations());
    }

    @GetMapping("/conversations/{conversationId}/messages")
    @ResponseBody
    public ResponseEntity<?> getConversationMessages(
            @PathVariable String conversationId) {

        try {
            return ResponseEntity.ok(
                    documentService.getMessages(conversationId));

        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Invalid conversation ID."));
        }
    }

    @DeleteMapping("/conversations/{conversationId}")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> deleteConversation(
            @PathVariable String conversationId) {

        try {
            documentService.deleteConversation(conversationId);

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Conversation deleted successfully."));

        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("success", false,
                            "error", "Invalid conversation ID."));
        }
    }

}
