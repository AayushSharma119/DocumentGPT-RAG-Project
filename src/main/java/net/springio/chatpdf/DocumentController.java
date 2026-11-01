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

        String contentType = document.getContentType();
        if (contentType == null || !contentType.equalsIgnoreCase("application/pdf")) {
            return ResponseEntity.badRequest()
                    .body(Map.of("success", false, "error", "Only PDF files are accepted."));
        }
        if (document.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("success", false, "error", "Uploaded file is empty."));
        }

        try {
            documentService.loadDocument(document.getResource());
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "filename", Objects.requireNonNullElse(document.getOriginalFilename(), "document.pdf"),
                    "size", document.getSize()
            ));
        } catch (RuntimeException e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("success", false, "error", "Failed to process document: " + e.getMessage()));
        }
    }

    @GetMapping("/chat")
    public String loadChat() {
        return "chat";
    }
}
