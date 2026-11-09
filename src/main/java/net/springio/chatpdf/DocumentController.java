package net.springio.chatpdf;

import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

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
            @RequestParam("fileToUpload") MultipartFile document) {

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
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "documentId", documentId,
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
}
