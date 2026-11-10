package net.springio.chatpdf;

import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@Controller
public class AuthController {

    private final JdbcTemplate jdbcTemplate;
    private final PasswordEncoder passwordEncoder;
    private final DocumentService documentService;

    public AuthController(
            JdbcTemplate jdbcTemplate,
            PasswordEncoder passwordEncoder,
            DocumentService documentService) {
        this.jdbcTemplate = jdbcTemplate;
        this.passwordEncoder = passwordEncoder;
        this.documentService = documentService;
    }

    @PostMapping("/register")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> register(
            @RequestParam String username,
            @RequestParam String email,
            @RequestParam String password) {
        documentService.initializeChatTables();

        username = username.trim();
        email = email.trim().toLowerCase();

        if (username.isBlank() || email.isBlank() || password.length() < 8) {
            return ResponseEntity.badRequest().body(
                    Map.of("success", false,
                            "error", "Enter valid details. Password must be at least 8 characters."));
        }

        Integer existing = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM app_users WHERE username = ? OR email = ?",
                Integer.class, username, email);

        if (existing != null && existing > 0) {
            return ResponseEntity.badRequest().body(
                    Map.of("success", false,
                            "error", "Username or email already exists."));
        }

        jdbcTemplate.update(
                "INSERT INTO app_users (id, username, email, password) VALUES (?, ?, ?, ?)",
                UUID.randomUUID(),
                username,
                email,
                passwordEncoder.encode(password));

        return ResponseEntity.ok(
                Map.of("success", true,
                        "message", "Registration successful. Please log in."));
    }

    @GetMapping("/register")
    public String registerPage() {
        return "register";
    }

    @GetMapping("/login")
    public String loginPage() {
        return "login";
    }

}